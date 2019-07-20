/*
 * Copyright (c) 2014, 2019, Marcus Hirt, Miroslav Wengner
 *
 * Robo4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Robo4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Robo4J. If not, see <http://www.gnu.org/licenses/>.
 */
package com.robo4j.socket.http.request;

import com.robo4j.socket.http.HttpHeaderFieldNames;
import com.robo4j.socket.http.HttpMethod;
import com.robo4j.socket.http.HttpVersion;
import com.robo4j.socket.http.ProtocolType;
import com.robo4j.socket.http.message.HttpDecoratedRequest;
import com.robo4j.socket.http.message.HttpDenominator;
import com.robo4j.socket.http.message.HttpRequestDenominator;
import com.robo4j.socket.http.util.ChannelBufferUtils;
import com.robo4j.socket.http.util.HttpMessageBuilder;
import com.robo4j.socket.http.util.RoboHttpUtils;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static com.robo4j.socket.http.HttpHeaderFieldValues.CONNECTION_KEEP_ALIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Marcus Hirt (@hirt)
 * @author Miro Wengner (@miragemiko)
 */
class ByteBufferTests {

	private static final String TEST_STRING = "Accept-Language: en-US,en;q=0.8\r\n\r\n{";
	private static final String TEST_POSTMAN_MESSAGE = "\r\n" + "{ \n" + "  \"value\" : \"move\"\n" + "}";
	private static final String TEST_POSTMAN_STRING = "POST /controller HTTP/1.1\r\n" + "Host: localhost:8042\r\n"
			+ "Connection: " + CONNECTION_KEEP_ALIVE + "\r\n" + "Content-Length: 23\r\n"
			+ "Postman-Token: 60b492c5-e7a9-6037-3021-42f8885542a9\r\n" + "Cache-Control: no-cache\r\n"
			+ "Origin: chrome-extension://fhbjgbiflinjbdggehcddcbncdddomop\r\n"
			+ "User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.101 Safari/537.36\r\n"
			+ "Content-Type: text/plain;charset=UTF-8\r\n" + "Accept: */*\r\n"
			+ "Accept-Encoding: gzip, deflate, br\r\n" + "Accept-Language: en-US,en;q=0.8\r\n" + TEST_POSTMAN_MESSAGE;
	private static final ByteBuffer TEST_BYTE_BUFFER = ByteBuffer.wrap(TEST_STRING.getBytes());

	@Test
	void testPostmanMessage() {
		HttpDecoratedRequest decoratedRequest = ChannelBufferUtils
				.extractDecoratedRequestByStringMessage(TEST_POSTMAN_STRING);
		decoratedRequest.addMessage(TEST_POSTMAN_MESSAGE);

		assertNotNull(decoratedRequest.getHeader());
		assertTrue(!decoratedRequest.getHeader().isEmpty());
		assertNotNull(decoratedRequest.getMessage());
		System.out.println("HEADER: " + decoratedRequest.getHeader());
		System.out.println("BODY: " + decoratedRequest.getMessage());

	}

	@Test
	void byteBufferFromRequestTest() {

		String bodyMessage = "this is test message";
		String host = "localhost";
		String clientPath = "/test";

		HttpDenominator denominator = new HttpRequestDenominator(HttpMethod.POST, clientPath, HttpVersion.HTTP_1_1);
		String postMessage = HttpMessageBuilder.Build().setDenominator(denominator)
				.addHeaderElement(HttpHeaderFieldNames.CONTENT_LENGTH, String.valueOf(bodyMessage.length()))
				.addHeaderElement(HttpHeaderFieldNames.HOST,
						RoboHttpUtils.createHost(host, ProtocolType.HTTP.getPort()))
				.build(bodyMessage);

		HttpDecoratedRequest decoratedRequest = ChannelBufferUtils.extractDecoratedRequestByStringMessage(postMessage);

		assertNotNull(decoratedRequest);
		assertEquals(HttpMethod.POST, decoratedRequest.getPathMethod().getMethod());
		assertEquals(postMessage.length(), decoratedRequest.getLength());
		assertEquals(clientPath, decoratedRequest.getPathMethod().getPath());
		assertEquals(bodyMessage, decoratedRequest.getMessage());
	}

	@Test
	void byteBufferFromRequestWithNoBodyTest() {

		String host = "localhost";
		String clientPath = "/test";

		HttpDenominator denominator = new HttpRequestDenominator(HttpMethod.GET, clientPath, HttpVersion.HTTP_1_1);
		String getMessage = HttpMessageBuilder.Build().setDenominator(denominator)
				.addHeaderElement(HttpHeaderFieldNames.HOST,
						RoboHttpUtils.createHost(host, ProtocolType.HTTP.getPort()))
				.build();

		HttpDecoratedRequest decoratedRequest = ChannelBufferUtils.extractDecoratedRequestByStringMessage(getMessage);

		assertNotNull(decoratedRequest);
		assertEquals(HttpMethod.GET, decoratedRequest.getPathMethod().getMethod());
		assertEquals(0, decoratedRequest.getLength());
		assertEquals(clientPath, decoratedRequest.getPathMethod().getPath());
		assertEquals(null, decoratedRequest.getMessage());
	}

	@Test
	void testMovingWindow() {
		String correctString = "\n\n";
		assertTrue(ChannelBufferUtils.isBWindow(ChannelBufferUtils.END_WINDOW,
				ByteBuffer.wrap(correctString.getBytes()).array()));
	}

	@Test
	void testReturnCharRemoval() {

		int position = 0;
		int bPosition = 0;
		int size = TEST_BYTE_BUFFER.capacity();
		byte[] tmpBytes = new byte[1024];

		while (position < size) {
			byte b = TEST_BYTE_BUFFER.get(position);
			if (b == ChannelBufferUtils.CHAR_RETURN) {
			} else {
				tmpBytes[bPosition] = b;
				bPosition++;
			}
			position++;
		}

		byte[] resBytes = ChannelBufferUtils.validArray(tmpBytes, bPosition);
		ByteBuffer resultBuffer = ByteBuffer.wrap(resBytes);
		assertEquals(TEST_BYTE_BUFFER.capacity(), resultBuffer.capacity() + ChannelBufferUtils.END_WINDOW.length);
	}
}
