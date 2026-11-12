package com.minimes.equipment;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

import com.minimes.equipment.protocol.DecodeResult;
import com.minimes.equipment.protocol.DecodeResult.Decoded;
import com.minimes.equipment.protocol.Frame;
import com.minimes.equipment.protocol.FrameDecoder;
import com.minimes.equipment.protocol.FrameEncoder;

/**
 * 테스트용 설비. 게이트웨이에 연결해서 전문을 보내고, 응답을 같은 {@link FrameDecoder}로 읽는다.
 */
class EquipmentClient implements AutoCloseable {

	private final Socket socket;
	private final FrameDecoder decoder = new FrameDecoder();
	private final Deque<DecodeResult> received = new ArrayDeque<>();

	EquipmentClient(int port) throws IOException {
		this(port, Duration.ofSeconds(5));
	}

	EquipmentClient(int port, Duration readTimeout) throws IOException {
		this("127.0.0.1", port, readTimeout);
	}

	EquipmentClient(String host, int port, Duration readTimeout) throws IOException {
		socket = new Socket(host, port);
		socket.setSoTimeout((int) readTimeout.toMillis());
		socket.setTcpNoDelay(true);
	}

	void send(Frame frame) throws IOException {
		sendRaw(FrameEncoder.encode(frame));
	}

	void sendRaw(byte[] bytes) throws IOException {
		socket.getOutputStream().write(bytes);
		socket.getOutputStream().flush();
	}

	/** 응답 전문 하나를 기다린다. 서버가 연결을 닫으면 null */
	Frame receive() throws IOException {
		InputStream in = socket.getInputStream();
		byte[] buffer = new byte[4096];
		while (received.isEmpty()) {
			int read = in.read(buffer);
			if (read == -1) {
				return null;
			}
			received.addAll(decoder.feed(buffer, 0, read));
		}
		return ((Decoded) received.poll()).frame();
	}

	Frame request(Frame frame) throws IOException {
		send(frame);
		return receive();
	}

	/** 서버가 연결을 닫았으면 true (읽기가 -1을 돌려줌) */
	boolean closedByServer() throws IOException {
		return socket.getInputStream().read() == -1;
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}

}
