/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.streams.elasticsearch.test;

import org.apache.streams.config.ComponentConfigurator;
import org.apache.streams.core.StreamsDatum;
import org.apache.streams.elasticsearch.ElasticsearchClientManager;
import org.apache.streams.elasticsearch.ElasticsearchPersistWriter;
import org.apache.streams.elasticsearch.ElasticsearchWriterConfiguration;
import org.apache.streams.jackson.StreamsJacksonMapper;
import org.apache.streams.pojo.json.Activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import org.apache.commons.io.IOUtils;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/**
 * Integration Test for
 * @see org.apache.streams.elasticsearch.ElasticsearchPersistWriter
 */
@Test
public class ElasticsearchPersistWriterIT {

  private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchPersistWriterIT.class);

  private static ObjectMapper MAPPER = StreamsJacksonMapper.getInstance();

  private ElasticsearchWriterConfiguration testConfiguration;
  private Client testClient;

  @BeforeClass
  public void prepareTestPersistWriter() throws Exception {

    testConfiguration = new ComponentConfigurator<>(ElasticsearchWriterConfiguration.class).detectConfiguration("ElasticsearchPersistWriterIT");
    testClient = ElasticsearchClientManager.getInstance(testConfiguration).client();

    ClusterHealthRequest clusterHealthRequest = Requests.clusterHealthRequest();
    ClusterHealthResponse clusterHealthResponse = testClient.admin().cluster().health(clusterHealthRequest).actionGet();
    assertNotEquals(clusterHealthResponse.getStatus(), ClusterHealthStatus.RED);

    IndicesExistsRequest indicesExistsRequest = Requests.indicesExistsRequest(testConfiguration.getIndex());
    IndicesExistsResponse indicesExistsResponse = testClient.admin().indices().exists(indicesExistsRequest).actionGet();
    if(indicesExistsResponse.isExists()) {
      DeleteIndexRequest deleteIndexRequest = Requests.deleteIndexRequest(testConfiguration.getIndex());
      DeleteIndexResponse deleteIndexResponse = testClient.admin().indices().delete(deleteIndexRequest).actionGet();
      assertTrue(deleteIndexResponse.isAcknowledged());
    }

  }

  @Test
  public void testPersistWriter() throws Exception {

    ElasticsearchPersistWriter testPersistWriter = new ElasticsearchPersistWriter(testConfiguration);
    testPersistWriter.prepare(null);

    Path testdataDir = Paths.get("target/dependency/activitystreams-testdata");
    List<Path> testdataPaths = Files.list(testdataDir).collect(Collectors.toList());
    for( Path docPath : testdataPaths ) {
      LOGGER.info("File: " + docPath );
      FileInputStream testActivityFileStream = new FileInputStream(docPath.toFile());
      Activity activity = MAPPER.readValue(testActivityFileStream, Activity.class);
      StreamsDatum datum = new StreamsDatum(activity, activity.getVerb());
      testPersistWriter.write( datum );
      LOGGER.info("Wrote: " + activity.getVerb() );
    }

    testPersistWriter.cleanUp();

    SearchRequestBuilder countRequest = testClient
        .prepareSearch(testConfiguration.getIndex())
        .setTypes(testConfiguration.getType());
    SearchResponse countResponse = countRequest.execute().actionGet();

    assertEquals(89, countResponse.getHits().getTotalHits());

  }

}
