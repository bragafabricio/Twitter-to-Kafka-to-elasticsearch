package consumers;

import com.google.gson.JsonParser;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

public class ElasticSearchConsumer {
    //  Elasticsearch client
    public static RestHighLevelClient createClient() {

        //  Credentials for ElasticSearch
        String hostname = "";
        String username = "";
        String password = "";

        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(username, password));

        RestClientBuilder builder = RestClient.builder(
                        new HttpHost(hostname, 443, "https"))
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    @Override
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                        return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }
                });
        RestHighLevelClient client = new RestHighLevelClient(builder);

        return client;
    }

    public static KafkaConsumer<String, String> createConsumer(String topic) {
        String bootstrapServers = "Localhost:9092";
        String groupId = "kafka-elasticsearch";

        //  Create Consumer configs
        Properties properties = new Properties();
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); //commit of offsets will be done together with batch process
        properties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");

        //  Create consumer itself
        KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(properties);
        consumer.subscribe(Arrays.asList(topic));

        return consumer;
    }

    // Extract from the Tweet the id to be used as id for ElasticSearch to turn it idempotent
    private static String extractIdFromTweet(String tweetJson) {
        return JsonParser.parseString(tweetJson)
                .getAsJsonObject()
                .get("id_str")
                .getAsString();
    }


    public static void main(String[] args) throws IOException {

        Logger logger = LoggerFactory.getLogger(ElasticSearchConsumer.class.getName());
        RestHighLevelClient client = createClient();

        KafkaConsumer<String, String> consumer = createConsumer("twitter_tweets");


        //pool for new data
        while (true) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
            Integer recordCount = records.count();
            logger.info("Received " + recordCount + " records.");

            BulkRequest bulkRequest = new BulkRequest();

            for (ConsumerRecord<String, String> record : records) {

                try {
                    String id = extractIdFromTweet(record.value());

                    // Data inserted into ElasticSearch
                    IndexRequest indexRequest = new IndexRequest("twitter")
                            .id(id)
                            .source(record.value(), XContentType.JSON);

                    bulkRequest.add(indexRequest); //Adding indexes to bulk request
                    logger.info(id);
                } catch (NullPointerException e) {
                    logger.warn("Skipping bad data: " + record.value());
                }
            }
            if (recordCount > 0) {

                BulkResponse bulkItemResponses = client.bulk(bulkRequest, RequestOptions.DEFAULT);
                logger.info("Committing offsets...");
                consumer.commitSync(); //Commit of offsets
                logger.info("Offsets have been committed");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

//        client.close();
    }
}
