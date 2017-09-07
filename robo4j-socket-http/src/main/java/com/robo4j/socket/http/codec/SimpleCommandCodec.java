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

package com.robo4j.socket.http.codec;

import com.robo4j.core.util.CoreConstants;
import com.robo4j.socket.http.units.HttpDecoder;
import com.robo4j.socket.http.units.HttpEncoder;
import com.robo4j.socket.http.units.HttpProducer;
import com.robo4j.socket.http.util.JsonUtil;

import java.util.Map;

/**
 * default simple codec for simple commands Simple codec is currently used for
 * Enum
 *
 * @see SimpleCommand
 *
 * @author Marcus Hirt (@hirt)
 * @author Miro Wengner (@miragemiko)
 */
@HttpProducer
public class SimpleCommandCodec implements HttpDecoder<SimpleCommand>, HttpEncoder<SimpleCommand> {
	private static final String KEY_TYPE = "type";
	private static final String KEY_VALUE = "value";

	@Override
	public String encode(SimpleCommand stuff) {
		final StringBuilder sb = new StringBuilder("{\"value\":\"").append(stuff.getValue());
		if (stuff.getType().equals(CoreConstants.STRING_EMPTY)) {
			sb.append("\"}");
		} else {
			sb.append("\",\"type\":\"").append(stuff.getType()).append("\"}");
		}
		return sb.toString();
	}

	@Override
	public SimpleCommand decode(String json) {
		Map<String, Object> map = JsonUtil.getMapNyJson(json);
		return map.containsKey(KEY_TYPE) ?
				new SimpleCommand(objectToString(map.get(KEY_VALUE)), objectToString(map.get(KEY_TYPE)))
				: new SimpleCommand(objectToString(map.get(KEY_VALUE)));
	}

	@Override
	public Class<SimpleCommand> getEncodedClass() {
		return SimpleCommand.class;
	}

	@Override
	public Class<SimpleCommand> getDecodedClass() {
		return SimpleCommand.class;
	}

	// Private Methods
	private String objectToString(Object object) {
		return object != null ? object.toString().trim() : CoreConstants.STRING_EMPTY;
	}
}
