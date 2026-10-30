package org.tsc.dispatch.actuator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Source RCON client: just enough to authenticate and run one command per connection.
 * The protocol is four header integers around a UTF-8 body; see the RCON specification.
 */
public final class RconClient implements AutoCloseable {

	private static final int TYPE_AUTH = 3;
	private static final int TYPE_AUTH_RESPONSE = 2;
	private static final int TYPE_COMMAND = 2;
	private static final int TYPE_RESPONSE = 0;

	private final Socket socket;
	private final DataInputStream input;
	private final DataOutputStream output;
	private int requestId = 1;

	public RconClient(String host, int port, String password, int timeoutMillis) throws IOException {
		socket = new Socket();
		socket.connect(new InetSocketAddress(host, port), timeoutMillis);
		socket.setSoTimeout(timeoutMillis);
		input = new DataInputStream(socket.getInputStream());
		output = new DataOutputStream(socket.getOutputStream());
		authenticate(password);
	}

	private void authenticate(String password) throws IOException {
		write(requestId, TYPE_AUTH, password);
		final Packet response = read();
		if (response.id == -1) {
			throw new IOException("RCON authentication rejected");
		}
		if (response.type != TYPE_AUTH_RESPONSE) {
			throw new IOException("unexpected RCON auth response type " + response.type);
		}
	}

	public String command(String command) throws IOException {
		write(++requestId, TYPE_COMMAND, command);
		final Packet response = read();
		if (response.type != TYPE_RESPONSE) {
			return "";
		}
		return response.body;
	}

	private void write(int id, int type, String body) throws IOException {
		final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		output.writeInt(payload.length + 10);
		output.writeInt(id);
		output.writeInt(type);
		output.write(payload);
		output.writeByte(0);
		output.writeByte(0);
		output.flush();
	}

	private Packet read() throws IOException {
		final int length = input.readInt();
		if (length < 10 || length > 8192) {
			throw new IOException("invalid RCON packet length " + length);
		}
		final int id = input.readInt();
		final int type = input.readInt();
		final byte[] body = new byte[length - 10];
		input.readFully(body);
		input.readByte();
		input.readByte();
		return new Packet(id, type, new String(body, StandardCharsets.UTF_8));
	}

	private record Packet(int id, int type, String body) {
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (IOException ignored) {
			// closing a dead socket is not an error worth surfacing
		}
	}
}
