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

package org.apache.streams.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.streams.core.StreamsDatum;
import org.apache.streams.core.StreamsProcessor;
import org.apache.streams.data.util.ActivityUtil;
import org.apache.streams.jackson.StreamsJacksonMapper;
import org.apache.streams.pojo.json.Activity;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.percolate.PercolateResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * References:
 * Some helpful references to help
 * Purpose              URL
 * -------------        ----------------------------------------------------------------
 * [Status Codes]       http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html
 * [Test Cases]         http://greenbytes.de/tech/tc/httpredirects/
 * [t.co behavior]      https://dev.twitter.com/docs/tco-redirection-behavior
 */

public class PercolateProcessor implements StreamsProcessor {

    private final static Logger LOGGER = LoggerFactory.getLogger(PercolateProcessor.class);

    private ObjectMapper mapper = StreamsJacksonMapper.getInstance();

    protected Queue<StreamsDatum> inQueue;
    protected Queue<StreamsDatum> outQueue;

    public String TAGS_EXTENSION = "tags";

    private ElasticsearchWriterConfiguration config;
    private ElasticsearchClientManager manager;
    private BulkRequestBuilder bulkBuilder;

    public PercolateProcessor(ElasticsearchConfiguration config) {
        manager = new ElasticsearchClientManager(config);
    }

    public ElasticsearchClientManager getManager() {
        return manager;
    }

    public void setManager(ElasticsearchClientManager manager) {
        this.manager = manager;
    }

    public ElasticsearchConfiguration getConfig() {
        return config;
    }

    public void setConfig(ElasticsearchWriterConfiguration config) {
        this.config = config;
    }

    public Queue<StreamsDatum> getProcessorOutputQueue() {
        return outQueue;
    }

    @Override
    public List<StreamsDatum> process(StreamsDatum entry) {

        List<StreamsDatum> result = Lists.newArrayList();

        String json;
        ObjectNode node;
        // first check for valid json
        if (entry.getDocument() instanceof String) {
            json = (String) entry.getDocument();
            try {
                node = (ObjectNode) mapper.readTree(json);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        } else {
            node = (ObjectNode) entry.getDocument();
            json = node.asText();
        }

        PercolateResponse response = manager.getClient().preparePercolate().setSource(json).execute().actionGet();

        ArrayNode tagArray = JsonNodeFactory.instance.arrayNode();

        for (PercolateResponse.Match match : response.getMatches()) {
            tagArray.add(match.getId().string());
        }

        Activity activity = mapper.convertValue(node, Activity.class);

        Map<String, Object> extensions = ActivityUtil.ensureExtensions(activity);

        extensions.put(TAGS_EXTENSION, tagArray);

        activity.setAdditionalProperty(ActivityUtil.EXTENSION_PROPERTY, extensions);

        result.add(entry);

        return result;

    }

    @Override
    public void prepare(Object o) {

        Preconditions.checkNotNull(manager);
        Preconditions.checkNotNull(manager.getClient());
        Preconditions.checkNotNull(config);
        Preconditions.checkNotNull(config.getTags());
        Preconditions.checkArgument(config.getTags().size() > 0);

        // consider using mapping to figure out what fields are included in _all
        //manager.getClient().admin().indices().prepareGetMappings(config.getIndex()).get().getMappings().get(config.getType()).;

        deleteOldQueries(config.getIndex());
        for (Tag tag : config.getTags()) {
            PercolateQueryBuilder queryBuilder = new PercolateQueryBuilder(tag.getId());
            queryBuilder.addQuery(tag.getQuery(), FilterLevel.MUST, "_all");
            addPercolateRule(queryBuilder, config.getIndex());
        }
        if (writePercolateRules() == true)
            LOGGER.info("wrote " + bulkBuilder.numberOfActions() + " tags to _percolator");
        else
            LOGGER.error("FAILED writing " + bulkBuilder.numberOfActions() + " tags to _percolator");


    }

    @Override
    public void cleanUp() {
        manager.getClient().close();
    }

    public int numOfPercolateRules() {
        return this.bulkBuilder.numberOfActions();
    }

    public void addPercolateRule(PercolateQueryBuilder builder, String index) {
        this.bulkBuilder.add(manager.getClient().prepareIndex("_percolator", index, builder.getId()).setSource(builder.getSource()));
    }

    /**
     *
     * @return returns true if all rules were addded. False indicates one or more rules have failed.
     */
    public boolean writePercolateRules() {
        if(this.numOfPercolateRules() < 0) {
            throw new RuntimeException("No Rules Have been added!");
        }
        BulkResponse response = this.bulkBuilder.execute().actionGet();
        for(BulkItemResponse r : response.getItems()) {
            if(r.isFailed()) {
                System.out.println(r.getId()+"\t"+r.getFailureMessage());
            }
        }
        return !response.hasFailures();
    }

    /**
     *
     * @param ids
     * @param index
     * @return  Returns true if all of the old tags were removed. False indicates one or more tags were not removed.
     */
    public boolean removeOldTags(Set<String> ids, String index) {
        if(ids.size() == 0) {
            return false;
        }
        BulkRequestBuilder bulk = manager.getClient().prepareBulk();
        for(String id : ids) {
            bulk.add(manager.getClient().prepareDelete("_percolator", index, id));
        }
        return !bulk.execute().actionGet().hasFailures();
    }

    public Set<String> getActivePercolateTags(String index) {
        Set<String> tags = new HashSet<String>();
        SearchRequestBuilder searchBuilder = manager.getClient().prepareSearch("_percolator").setTypes(index).setSize(1000);
        SearchResponse response = searchBuilder.setQuery(QueryBuilders.matchAllQuery()).execute().actionGet();
        SearchHits hits = response.getHits();
        for(SearchHit hit : hits.getHits()) {
            tags.add(hit.id());
        }
        return tags;
    }

    /**
     *
     * @param index
     * @return
     */
    public boolean deleteOldQueries(String index) {
        Set<String> tags = getActivePercolateTags(index);
        if(tags.size() == 0) {
            LOGGER.warn("No active tags were found in _percolator for index : {}", index);
            return false;
        }
        LOGGER.info("Deleting {} tags.", tags.size());
        BulkRequestBuilder bulk = manager.getClient().prepareBulk();
        for(String tag : tags) {
            bulk.add(manager.getClient().prepareDelete("_percolator", index, tag));
        }
        BulkResponse response =bulk.execute().actionGet();
        return !response.hasFailures();
    }

    public static class PercolateQueryBuilder {

        private BoolQueryBuilder queryBuilder;
        private String id;

        public PercolateQueryBuilder(String id) {
            this.id = id;
            this.queryBuilder = QueryBuilders.boolQuery();
        }

        public void setMinumumNumberShouldMatch(int shouldMatch) {
            this.queryBuilder.minimumNumberShouldMatch(shouldMatch);
        }


        public void addQuery(String query, FilterLevel level, String... fields) {
            QueryStringQueryBuilder builder = QueryBuilders.queryString(query);
            if(fields != null && fields.length > 0) {
                for(String field : fields) {
                    builder.field(field);
                }
            }
            switch (level) {
                case MUST:
                    this.queryBuilder.must(builder);
                    break;
                case SHOULD:
                    this.queryBuilder.should(builder);
                    break;
                case MUST_NOT:
                    this.queryBuilder.mustNot(builder);
            }
        }

        public String getId() {
            return this.id;
        }

        public String getSource() {
            return "{ \n\"query\" : "+this.queryBuilder.toString()+"\n}";
        }


    }

    public enum FilterLevel {
        MUST, SHOULD, MUST_NOT
    }

}
