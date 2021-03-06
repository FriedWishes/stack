/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.rest.applications;

import org.codehaus.jackson.JsonNode;
import org.junit.Test;
import org.usergrid.rest.AbstractRestTest;

import javax.ws.rs.core.MediaType;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.usergrid.utils.MapUtils.hashMap;

/**
 * Invokes methods on ApplicationResource
 * @author zznate
 */
public class ApplicationResourceTest extends AbstractRestTest {

  @Test
  public void test_GET_credentials_ok() {
    String mgmtToken = mgmtToken();

    JsonNode node = resource().path("/test-app/credentials")
            .queryParam("access_token", mgmtToken)
            .accept(MediaType.APPLICATION_JSON)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .get(JsonNode.class);
    assertEquals("ok", node.get("status").getTextValue());
    logNode(node);
  }

}
