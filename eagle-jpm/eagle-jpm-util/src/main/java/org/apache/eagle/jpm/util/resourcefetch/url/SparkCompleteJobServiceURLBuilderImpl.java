/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  * contributor license agreements.  See the NOTICE file distributed with
 *  * this work for additional information regarding copyright ownership.
 *  * The ASF licenses this file to You under the Apache License, Version 2.0
 *  * (the "License"); you may not use this file except in compliance with
 *  * the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.apache.eagle.jpm.util.resourcefetch.url;

import org.apache.eagle.jpm.util.Constants;

public class SparkCompleteJobServiceURLBuilderImpl implements ServiceURLBuilder {

    public String build(String url, String... parameters) {
        String newUrl = URLUtil.removeTrailingSlash(url);

        return newUrl + "/" + Constants.V2_APPS_URL
                + "?applicationTypes=SPARK&state=FINISHED&finishedTimeBegin="
                + parameters[0] + "&" + Constants.ANONYMOUS_PARAMETER;
    }
}