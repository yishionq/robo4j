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

import com.robo4j.socket.http.dto.ClassGetSetDTO;
import com.robo4j.socket.http.units.HttpDecoder;
import com.robo4j.socket.http.units.HttpEncoder;
import com.robo4j.socket.http.units.HttpProducer;
import com.robo4j.socket.http.util.JsonUtil;
import com.robo4j.socket.http.util.ReflectUtils;

import java.util.Map;

/**
 * Camera Image codec
 *
 * @author Marcus Hirt (@hirt)
 * @author Miro Wengner (@miragemiko)
 */
@HttpProducer
public class CameraMessageCodec implements HttpDecoder<CameraMessage>, HttpEncoder<CameraMessage> {

	private static final Map<String, ClassGetSetDTO> fieldMethodMap = ReflectUtils.getFieldsTypeMap(CameraMessage.class);
	private static final String KEY_TYPE = "type";
	private static final String KEY_VALUE = "value";
	private static final String KEY_IMAGE = "image";

	@Override
	public String encode(CameraMessage message) {
		return ReflectUtils.createJsonByFieldClassGetter(fieldMethodMap, message);
	}

	@Override
	public CameraMessage decode(String json) {
		final Map<String, Object> map = JsonUtil.getMapByJson(json);

		final String type = String.valueOf(map.get(KEY_TYPE));
		final String value = String.valueOf(map.get(KEY_VALUE));
		final String image = String.valueOf(map.get(KEY_IMAGE));
		return new CameraMessage(type, value, image);

	}

	@Override
	public Class<CameraMessage> getEncodedClass() {
		return CameraMessage.class;
	}

	@Override
	public Class<CameraMessage> getDecodedClass() {
		return CameraMessage.class;
	}

}
