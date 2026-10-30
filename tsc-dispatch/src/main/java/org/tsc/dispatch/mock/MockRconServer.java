package org.tsc.dispatch.mock;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal Source RCON server for tests and local demonstrations.
 *
 * <p>Accepts one connection at a time, validates the password, records every command and
 * answers with an empty body - exactly what a vanilla server does for a command that prints
 * nothing.</p>
 */
public final class MockRconServer implements AutoCloseable {

	private final ServerSocket serverSocket;
	private final String password;
	private final List<String> commands = Collections.synchronizedList(new ArrayList<>());
	private volatile boolean running = true;

	public MockRconServer(int port, String password) throws IOException {
		this.serverSocket = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
		this.password = password;
	}

	public int port() {
		return serverSocket.getLocalPort();
	}

	public List<String> commands() {
		return List.copyOf(commands);
	}

	public void start() {
		final Thread thread = new Thread(this::acceptLoop, "mock-rcon");
		thread.setDaemon(true);
		thread.start();
	}

	private void acceptLoop() {
		while (running) {
			try (Socket socket = serverSocket.accept()) {
				socket.setSoTimeout(5000);
				serve(socket);
			} catch (IOException exception) {
				if (running) {
					// a client that disconnects mid-handshake is normal
					continue;
				}
				return;
			}
		}
	}

	private void serve(Socket socket) throws IOException {
		final DataInputStream input = new DataInputStream(socket.getInputStream());
		final DataOutputStream output = new DataOutputStream(socket.getOutputStream());
		while (running) {
			final int length;
			try {
				length = input.readInt();
			} catch (IOException endOfStream) {
				return;
			}
			if (length < 10 || length > 8192) {
				return;
			}
			final int id = input.readInt();
			final int type = input.readInt();
			final byte[] body = new byte[length - 10];
			input.readFully(body);
			input.readByte();
			input.readByte();
			final String text = new String(body, StandardCharsets.UTF_8);

			if (type == 3) {
				final boolean ok = password == null || password.isEmpty() || password.equals(text);
				write(output, ok ? id : -1, 2, "");
				if (!ok) {
					return;
				}
			} else if (type == 2) {
				commands.add(text);
				write(output, id, 0, "");
			}
		}
	}

	private static void write(DataOutputStream output, int id, int type, String body) throws IOException {
		final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		output.writeInt(payload.length + 10);
		output.writeInt(id);
		output.writeInt(type);
		output.write(payload);
		output.writeByte(0);
		output.writeByte(0);
		output.flush();
	}

	@Override
	public void close() {
		running = false;
		try {
			serverSocket.close();
		} catch (IOException ignored) {
			// closing an already closed socket is not an error
		}
	}
}
