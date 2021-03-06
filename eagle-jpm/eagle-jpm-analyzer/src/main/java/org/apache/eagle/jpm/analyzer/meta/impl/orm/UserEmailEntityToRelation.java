/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eagle.jpm.analyzer.meta.impl.orm;


import org.apache.commons.lang.StringUtils;
import org.apache.eagle.common.function.ThrowableConsumer2;
import org.apache.eagle.jpm.analyzer.meta.model.UserEmailEntity;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public class UserEmailEntityToRelation implements ThrowableConsumer2<PreparedStatement, UserEmailEntity, SQLException> {
    @Override
    public void accept(PreparedStatement statement, UserEmailEntity entity) throws SQLException {
        int parameterIndex = 1;
        if (StringUtils.isNotBlank(entity.getUuid())) {
            statement.setString(parameterIndex, entity.getUuid());
            parameterIndex++;
        }
        if (StringUtils.isNotBlank(entity.getMailAddress())) {
            statement.setString(parameterIndex, entity.getMailAddress());
            parameterIndex++;
        }
        if (entity.getCreatedTime() > 0) {
            statement.setLong(parameterIndex, entity.getCreatedTime());
            parameterIndex++;
        }
        if (entity.getModifiedTime() > 0) {
            statement.setLong(parameterIndex, entity.getModifiedTime());
            parameterIndex++;
        }
        if (StringUtils.isNotBlank(entity.getSiteId())) {
            statement.setString(parameterIndex, entity.getSiteId());
            parameterIndex++;
        }
        if (StringUtils.isNotBlank(entity.getUserId())) {
            statement.setString(parameterIndex, entity.getUserId());
            parameterIndex++;
        }
    }
}
