package com.minimes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * DB 테스트용 데이터베이스를 새로 만든다. 개발 DB(mes)의 데이터는 건드리지 않는다.
 * 동등성 검증(scripts/equivalence.py)과 같은 방법: 지우고 만든 뒤 db/init 스크립트를 파일 이름순으로 실행한다.
 */
public final class TestDatabase {

	private static final String HOST = "jdbc:postgresql://localhost:55432/";
	private static final String USER = "mes";
	private static final String PASSWORD = "mes";

	private TestDatabase() {
	}

	/** 새로 만든 DB의 JDBC URL */
	public static String recreate(String name) {
		try (Connection admin = DriverManager.getConnection(HOST + "mes", USER, PASSWORD);
				Statement statement = admin.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + name);
		}
		catch (SQLException e) {
			throw new IllegalStateException("DB 컨테이너에 연결할 수 없습니다. 레포 루트에서 docker compose up -d 를 먼저 실행하세요", e);
		}

		String url = HOST + name;
		try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD)) {
			for (Path script : initScripts()) {
				ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(script), "UTF-8"));
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException(e);
		}
		return url;
	}

	private static List<Path> initScripts() {
		// 테스트는 mes-api 폴더에서 실행된다
		try (Stream<Path> files = Files.list(Path.of("..", "db", "init"))) {
			return files.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
