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
package com.robo4j.core.client.request;

import com.robo4j.core.client.util.HttpUtils;
import com.robo4j.core.logging.SimpleLoggingUtil;
import com.robo4j.core.util.ConstantUtil;
import com.robo4j.http.HttpMessage;
import com.robo4j.http.HttpVersion;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * Request Factory is singleton
 *
 * @author Miro Wengner (@miragemiko)
 */
public class RoboRequestFactory implements DefaultRequestFactory<String> {

    public RoboRequestFactory() {
    }

    @Override
    public String processGet(HttpMessage httpMessage) {

        if (HttpVersion.containsValue(httpMessage.getVersion())) {
            final URI uri = httpMessage.getUri();
            final List<String> paths = Arrays
                    .stream(httpMessage.getUri().getPath().split(ConstantUtil.getHttpSeparator(12)))
                    .filter(e -> !e.isEmpty()).collect(Collectors.toList());
            SimpleLoggingUtil.debug(getClass(), "path: " + paths);
            if(uri != null && uri.getQuery() != null && !uri.getQuery().isEmpty()){
                final Map<String, String> queryValues = HttpUtils.parseURIQueryToMap(uri.getQuery(),
                        ConstantUtil.HTTP_QUERY_SEP);
                SimpleLoggingUtil.debug(getClass(), "queryValues: " + queryValues);


                //TODO miro -> this must be redesign/improved to be more generic
                switch (queryValues.get("type")){
                    case "lcd":
                        return queryValues.get("command");
                    case "tank":
                        return queryValues.get("command");
                    default:

                }
            }
        } else {
            SimpleLoggingUtil.error(getClass(), "processGet is corrupted: " + httpMessage);
        }

        return null;
    }
}
