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
package com.robo4j.net;

import com.robo4j.AttributeDescriptor;
import com.robo4j.LifecycleState;
import com.robo4j.RoboContext;
import com.robo4j.RoboReference;
import com.robo4j.configuration.Configuration;
import com.robo4j.configuration.ConfigurationFactory;
import com.robo4j.scheduler.Scheduler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 *
 * @author Marcus Hirt (@hirt)
 * @author Miroslav Wengner (@miragemiko)
 */
public class ClientRemoteRoboContext implements RoboContext {

	private class ClientRemoteRoboReference<T> implements RoboReference<T> {

		private final String id;

		ClientRemoteRoboReference(String id) {
			this.id = id;
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public LifecycleState getState() {
			throw new UnsupportedOperationException("Not supported yet!");
		}

		@Override
		public void sendMessage(Object message) {
			try {
				if (!client.isConnected()) {
					client.connect();
				}
				client.sendMessage(id, message);
			} catch (IOException e) {
				// TODO: Error handling
				e.printStackTrace();
			}
		}

		@Override
		public Class<T> getMessageType() {
			throw new UnsupportedOperationException("Not supported yet!");
		}

		@Override
		public Configuration getConfiguration() {
			throw new UnsupportedOperationException("Not supported yet!");
		}

		@Override
		public <R> Future<R> getAttribute(AttributeDescriptor<R> attribute) {
			throw new UnsupportedOperationException("Not supported yet!");
		}

		@Override
		public Collection<AttributeDescriptor<?>> getKnownAttributes() {
			throw new UnsupportedOperationException("Not supported yet!");
		}

		@Override
		public Future<Map<AttributeDescriptor<?>, Object>> getAttributes() {
			throw new UnsupportedOperationException("Not supported yet!");
		}

	}

	private final RoboContextDescriptorEntry descriptorEntry;
	private final MessageClient client;
	private final Map<String, RoboReference> remoteClientReferences = new ConcurrentHashMap<>();

	public ClientRemoteRoboContext(RoboContextDescriptorEntry descriptorEntry) {
		this.descriptorEntry = descriptorEntry;
		client = initializeClient(descriptorEntry);
	}

	private MessageClient initializeClient(RoboContextDescriptorEntry descriptorEntry) {
		MessageClient client = new MessageClient(URI.create(descriptorEntry.descriptor.getMetadata().get(RoboContextDescriptor.KEY_URI)),
				descriptorEntry.descriptor.getId(), ConfigurationFactory.createEmptyConfiguration());
		return client;
	}

	@Override
	public LifecycleState getState() {
		return null;
	}

	@Override
	public void shutdown() {
	}

	@Override
	public void stop() {
	}

	@Override
	public void start() {
	}

	@Override
	public <T> RoboReference<T> getReference(String id) {
		if(remoteClientReferences.containsKey(id)){
			return remoteClientReferences.get(id);
		} else {
			RoboReference<T> roboReference = new ClientRemoteRoboReference<>(id);
			remoteClientReferences.put(id, roboReference);
			return roboReference;
		}
	}

	@Override
	public Collection<RoboReference<?>> getUnits() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Scheduler getScheduler() {
		throw new UnsupportedOperationException("Accessing the Scheduler remotely is not supported. Use the local scheduler.");
	}

	@Override
	public String getId() {
		return descriptorEntry.descriptor.getId();
	}

	public InetAddress getAddress() {
		return descriptorEntry.address;
	}

	@Override
	public Configuration getConfiguration() {
		// TODO Auto-generated method stub
		return null;
	}
}
