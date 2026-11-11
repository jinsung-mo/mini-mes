package com.minimes.equipment;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.minimes.common.ErrorCode;
import com.minimes.equipment.protocol.DecodeResult;
import com.minimes.equipment.protocol.DecodeResult.Decoded;
import com.minimes.equipment.protocol.DecodeResult.Malformed;
import com.minimes.equipment.protocol.Frame;
import com.minimes.equipment.protocol.FrameDecoder;
import com.minimes.equipment.protocol.FrameEncoder;

/**
 * 설비 TCP 게이트웨이. 소켓과 전문 경계만 다루고, 전문의 의미는 {@link FrameHandler}에 맡긴다.
 * <p>
 * 스레드 구조:
 * <ul>
 * <li>accept 스레드 하나: {@code accept()}는 새 연결이 올 때까지 멈추므로 따로 둔다</li>
 * <li>연결마다 스레드 하나: {@code read()}도 데이터가 올 때까지 멈춘다. 이 스레드가 자기 연결의
 * {@link FrameDecoder}를 가진다 (디코더는 상태가 있어서 공유하면 안 됨)</li>
 * <li>한 연결 안에서는 받은 순서대로 하나씩 처리하고 응답한다. 같은 설비의 실적 순서가 바뀌지 않는다</li>
 * <li>연결 스레드는 최대 {@code maxConnections}개. 넘치면 새 연결을 바로 닫아서, 연결이 몰려도 WAS 전체가
 * 스레드 때문에 무너지지 않게 한다</li>
 * </ul>
 * 연결이 수천 개면 연결마다 스레드를 두는 방식은 맞지 않는다. 그때는 적은 스레드로 많은 연결을 다루는 NIO(Netty)를 쓴다.
 * <p>
 * Spring이 시작할 때 열고, 종료할 때 닫는다({@link SmartLifecycle}). 포트를 열 수 없으면 WAS 시작이 실패한다.
 */
@Component
public class EquipmentGateway implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(EquipmentGateway.class);

	private final EquipmentProperties properties;
	private final FrameHandler handler;

	private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
	private volatile boolean running;
	private ServerSocket serverSocket;
	private ThreadPoolExecutor connectionPool;
	private Thread acceptThread;

	public EquipmentGateway(EquipmentProperties properties, FrameHandler handler) {
		this.properties = properties;
		this.handler = handler;
	}

	@Override
	public void start() {
		try {
			serverSocket = new ServerSocket();
			serverSocket.bind(new InetSocketAddress(properties.bindAddress(), properties.port()));
		}
		catch (IOException e) {
			throw new UncheckedIOException("설비 게이트웨이 포트를 열 수 없음: " + properties.port(), e);
		}
		// 대기열 없이(SynchronousQueue) 최대 개수까지만 스레드를 만든다. 넘치면 RejectedExecutionException
		AtomicInteger threadNo = new AtomicInteger();
		connectionPool = new ThreadPoolExecutor(0, properties.maxConnections(), 60, TimeUnit.SECONDS,
				new SynchronousQueue<>(), task -> new Thread(task, "equipment-conn-" + threadNo.incrementAndGet()));
		running = true;
		acceptThread = new Thread(this::acceptLoop, "equipment-accept");
		acceptThread.start();
		log.info("설비 게이트웨이 시작 {}:{}", properties.bindAddress(), getPort());
	}

	@Override
	public void stop() {
		running = false;
		closeQuietly(serverSocket); // accept()가 SocketException으로 깨어나 accept 스레드가 끝난다
		connections.forEach(EquipmentGateway::closeQuietly); // read()도 같은 방식으로 깨운다
		connectionPool.shutdown();
		try {
			if (!connectionPool.awaitTermination(5, TimeUnit.SECONDS)) {
				log.warn("설비 연결 스레드가 5초 안에 끝나지 않음");
			}
			acceptThread.join(5000);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		log.info("설비 게이트웨이 종료");
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** 실제로 연 포트. 설정이 0이면 OS가 고른 포트 */
	public int getPort() {
		return serverSocket.getLocalPort();
	}

	private void acceptLoop() {
		while (running) {
			Socket socket;
			try {
				socket = serverSocket.accept();
			}
			catch (IOException e) {
				if (running) {
					log.error("설비 연결 받기 실패", e);
					continue;
				}
				return; // stop()이 서버 소켓을 닫음
			}
			try {
				connections.add(socket);
				connectionPool.execute(() -> serve(socket));
			}
			catch (RejectedExecutionException e) {
				log.warn("동시 연결 {}개를 넘어서 새 연결을 닫음 [{}]", properties.maxConnections(),
						socket.getRemoteSocketAddress());
				connections.remove(socket);
				closeQuietly(socket);
			}
		}
	}

	/** 연결 하나를 끝날 때까지 처리한다 */
	private void serve(Socket socket) {
		Object peer = socket.getRemoteSocketAddress();
		log.info("설비 연결 [{}]", peer);
		FrameDecoder decoder = new FrameDecoder();
		try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
			socket.setSoTimeout((int) properties.readTimeout().toMillis());
			socket.setTcpNoDelay(true); // 작은 ACK를 모아 보내느라 늦추지 않는다 (Nagle 알고리즘 끄기)
			byte[] buffer = new byte[4096];
			int read;
			while ((read = in.read(buffer)) != -1) {
				for (DecodeResult result : decoder.feed(buffer, 0, read)) {
					Frame reply = process(result, peer);
					if (reply != null) {
						out.write(FrameEncoder.encode(reply));
						out.flush();
					}
				}
			}
			log.info("설비가 연결을 닫음 [{}]", peer);
		}
		catch (SocketTimeoutException e) {
			log.warn("{}초 동안 받은 것이 없어서 연결을 닫음 [{}]", properties.readTimeout().toSeconds(), peer);
		}
		catch (SocketException e) {
			if (running) {
				log.warn("설비 연결이 끊김 [{}]: {}", peer, e.getMessage());
			}
		}
		catch (IOException e) {
			log.warn("설비 연결 오류 [{}]", peer, e);
		}
		catch (RuntimeException e) {
			// 처리기는 업무 예외를 NAK로 바꾸므로 여기까지 오면 게이트웨이의 버그다. 연결을 닫고 원인을 남긴다
			log.error("설비 전문 처리 중 예상하지 못한 예외, 연결을 닫음 [{}]", peer, e);
		}
		finally {
			connections.remove(socket);
			if (decoder.pendingBytes() > 0) {
				log.warn("마지막 전문이 잘린 채 연결이 끝남: {}바이트 버림 [{}]", decoder.pendingBytes(), peer);
			}
		}
	}

	private Frame process(DecodeResult result, Object peer) {
		if (result instanceof Decoded decoded) {
			return handler.handle(decoded.frame());
		}
		Malformed malformed = (Malformed) result;
		log.warn("형식이 틀린 전문 {}바이트를 버림 [{}, messageId={}]: {}", malformed.discardedBytes(), peer,
				malformed.messageId(), malformed.reason());
		if (malformed.messageId() == null) {
			return null; // 누구에게 답할지 모른다
		}
		return EquipmentFrameHandler.nak(malformed.messageId(), ErrorCode.E001, malformed.reason());
	}

	private static void closeQuietly(Closeable closeable) {
		try {
			if (closeable != null) {
				closeable.close();
			}
		}
		catch (IOException e) {
			// 닫는 중의 오류는 무시
		}
	}

}
