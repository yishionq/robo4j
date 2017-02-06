/*
 * Copyright (c) 2014, 2017, Marcus Hirt, Miroslav Wengner
 * 
 * Robo4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Robo4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Robo4J. If not, see <http://www.gnu.org/licenses/>.
 */
package com.robo4j.core.unit;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.robo4j.core.ConfigurationException;
import com.robo4j.core.LifecycleState;
import com.robo4j.core.RoboContext;
import com.robo4j.core.RoboReference;
import com.robo4j.core.RoboUnit;
import com.robo4j.core.client.request.RoboRequestCallable;
import com.robo4j.core.client.request.RoboRequestFactory;
import com.robo4j.core.concurrency.RoboThreadFactory;
import com.robo4j.core.configuration.Configuration;
import com.robo4j.core.logging.SimpleLoggingUtil;

/**
 * Non-Blocking Http Unit provides REST end-point
 *
 * @author Miro Wengner (@miragemiko)
 */

// TODO : simplify the interface and
public class HttpUnit extends RoboUnit<Object> {

	private static final int DEFAULT_THREAD_POOL_SIZE = 2;
	private static final int KEEP_ALIVE_TIME = 10;
	private static final int _DEFAULT_PORT = 8042;
	private static final Set<LifecycleState> activeStates = EnumSet.of(LifecycleState.STARTED, LifecycleState.STARTING);
	private final ExecutorService executor = new ThreadPoolExecutor(DEFAULT_THREAD_POOL_SIZE, DEFAULT_THREAD_POOL_SIZE,
			KEEP_ALIVE_TIME, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
			new RoboThreadFactory("Robo4J HttpUnit ", true));
	private boolean available;
	private Integer port;
	private String target;
	private ServerSocketChannel server;

	public HttpUnit(RoboContext context, String id) {
		super(context, id);
	}

	@Override
	public void start() {
		setState(LifecycleState.STARTING);
		final RoboReference<String> targetRef = getContext().getReference(target);
		if(!available){
			executor.execute(() -> server(targetRef));
			available = true;
		} else {
			System.out.println("Http start() error: " + targetRef);
		}
		setState(LifecycleState.STARTED);
	}

	/**
	 *
	 * @param configuration
	 *            the {@link Configuration} provided.
	 * @throws ConfigurationException
	 */
	protected void onInitialization(Configuration configuration) throws ConfigurationException {
		setState(LifecycleState.UNINITIALIZED);
		target = configuration.getString("target", null);
		port = configuration.getInteger("port", _DEFAULT_PORT);
		if (target == null) {
			throw ConfigurationException.createMissingConfigNameException("target");
		}
		setState(LifecycleState.INITIALIZED);
	}

	@Override
	public void shutdown() {
		setState(LifecycleState.SHUTTING_DOWN);
		try {
			if (server != null) {
				server.socket().close();
				server.close();
			}
		} catch (IOException e) {
			SimpleLoggingUtil.error(getClass(), "Http server problem: ", e);
		}

		executor.shutdownNow();
		setState(LifecycleState.SHUTDOWN);
	}

	// Private Methods
	/**
	 * Start non-blocking socket server on http protocol
	 * 
	 * @param targetRef
	 *            - reference to the target queue
	 */
	private void server(final RoboReference<String> targetRef) {
		try {
			server = ServerSocketChannel.open();
			server.socket().bind(new InetSocketAddress(port));
			SimpleLoggingUtil.debug(getClass(), "started port: " + port);
			while (activeStates.contains(getState())) {
				SocketChannel requestChannel = server.accept();
				Future<String> result = executor
						.submit(new RoboRequestCallable(requestChannel.socket(), new RoboRequestFactory()));
				targetRef.sendMessage(result.get());
				requestChannel.close();
			}

		} catch (InterruptedException | ExecutionException | IOException e) {
			SimpleLoggingUtil.error(getClass(), "SERVER CLOSED", e);
		}
		SimpleLoggingUtil.debug(getClass(), "stopped port: " + port);
		setState(LifecycleState.STOPPED);
	}
}
