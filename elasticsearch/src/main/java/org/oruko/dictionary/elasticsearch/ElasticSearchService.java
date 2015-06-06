package org.oruko.dictionary.elasticsearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.oruko.dictionary.model.NameEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStream;
import javax.annotation.PostConstruct;

/**
 * Service to interact with ElasticSearch Index
 *
 * @author Dadepo Aderemi.
 */
@Service
public class ElasticSearchService {

    private Client client;
    private String clusterName;
    private String hostName;
    private String indexName;
    private String documentType;
    private Integer port;
    private ResourceLoader resourceLoader;
    private ObjectMapper mapper = new ObjectMapper();
    private boolean elasticSearchNodeAvailable;
    private Logger logger = LoggerFactory.getLogger(ElasticSearchService.class);

    @Value("${es.clustername:yoruba_name_dictionary}")
    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    @Value("${es.hostname:localhost}")
    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    @Value("${es.portnumber:9300}")
    public void setPort(Integer port) {
        this.port = port;
    }

    @Value("${es.indexname:nameentry}")
    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    @Value("${es.documenttype:nameentry}")
    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    @Autowired
    public void setResourceLoader(ResourceLoader loader) {
        this.resourceLoader = loader;
    }

    ElasticSearchService() {
    }

    public boolean isElasticSearchNodeAvailable() {
        return elasticSearchNodeAvailable;
    }

    /**
     * Add a {@link org.oruko.dictionary.model.NameDto} into ElasticSearch index
     *
     * @param entry the {@link org.oruko.dictionary.model.NameDto} to index
     * @return returns true | false depending on if the indexing operation was successful.
     */
    public boolean indexName(NameEntry entry) {

        if (!isElasticSearchNodeAvailable()) {
            logger.info("Index attempt not possible. You do not have an elasticsearch node running");
            return false;
        }

        try {
            String entryAsJson = mapper.writeValueAsString(entry);
            client.prepareIndex(indexName, documentType, entry.getName().toLowerCase())
                  .setSource(entryAsJson)
                  .execute()
                  .actionGet();
            return true;
        } catch (JsonProcessingException e) {
            logger.info("Failed to parse NameEntry into Json", e);
            return false;
        }
    }

    /**
     * Deletes a document by name (which is the id within ElasticSearch index)
     *
     * @param name the name to delete from the index
     * @return true | false depending on if deleting operation was successful
     */
    public boolean deleteFromIndex(String name) {

        if (!isElasticSearchNodeAvailable()) {
            logger.info("Delete attempt not possible. You do not have an elasticsearch node running");
            return false;
        }

        DeleteResponse response = client.prepareDelete(indexName, documentType, name.toLowerCase())
                                        .execute()
                                        .actionGet();
        return response.isFound();
    }

    // On start up, creates an index for the application if one does not
    // already exists and add the custom mapping
    @PostConstruct
    private void buildElasticSearchClient() {
        String mapping = "";
        Settings settings = ImmutableSettings.settingsBuilder()
                                             .put("cluster.name", clusterName)
                                             .build();

        Resource resource = resourceLoader.getResource("classpath:NameEntryElasticSearchMapping.json");

        try {
            InputStream resourceInputStream = resource.getInputStream();
            mapping = new String(FileCopyUtils.copyToByteArray(resourceInputStream));
        } catch (IOException e) {
            logger.info("Failed to read ES mapping");
        }

        client = new TransportClient(settings)
                .addTransportAddress(new InetSocketTransportAddress(hostName, port));

        ImmutableList<DiscoveryNode> nodes = ((TransportClient) client).connectedNodes();

        if (nodes.isEmpty()) {
            elasticSearchNodeAvailable = false;
            client.close();
        } else {
            elasticSearchNodeAvailable = true;

            try {
                boolean exists = client.admin().indices().prepareExists(indexName).execute().actionGet().isExists();

                if (!exists) {
                    client.admin().indices()
                          .create(new CreateIndexRequest(indexName)).actionGet();
                }

                PutMappingResponse putMappingResponse = client.admin().indices()
                                                              .preparePutMapping(indexName)
                                                              .setType(documentType)
                                                              .setSource(mapping)
                                                              .execute().actionGet();

                logger.info("Adding {} to type {} in index {} was {} at startup",
                            mapping, documentType, indexName, putMappingResponse.isAcknowledged());
            } catch (Exception e) {
                logger.error("ElasticSearch not running", e);
            }
        }
    }
}
