package org.hps;

import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class Scaler {

    public  static Instant lastDecision;
    public static  String CONSUMER_GROUP;
    public static int numberOfPartitions;
    public static AdminClient admin = null;
    private static final Logger log = LogManager.getLogger(Scaler.class);
    public static Map<TopicPartition, Long> currentPartitionToCommittedOffset = new HashMap<>();
    public static Map<TopicPartition, Long> previousPartitionToCommittedOffset = new HashMap<>();
    public static Map<TopicPartition, Long> previousPartitionToLastOffset = new HashMap<>();
    public static Map<TopicPartition, Long> currentPartitionToLastOffset = new HashMap<>();
    public static Map<TopicPartition, Long> partitionToLag = new HashMap<>();
    public static Map<MemberDescription, Float> maxConsumptionRatePerConsumer = new HashMap<>();
    public static Map<MemberDescription, Long> consumerToLag = new HashMap<>();
    public static  String mode;
    static boolean firstIteration = true;
    static Long sleep;
    static Long waitingTime;
    static String topic;
    static String cluster;
    static Long poll;
    static Long SEC;
    static String choice;
    static String BOOTSTRAP_SERVERS;
    static Long allowableLag;


    static Map<String, ConsumerGroupDescription> consumerGroupDescriptionMap;

    public static void main(String[] args) throws ExecutionException, InterruptedException {

        int iteration =0;
        sleep = Long.valueOf(System.getenv("SLEEP"));
        waitingTime = Long.valueOf(System.getenv("WAITING_TIME"));
        topic = System.getenv("TOPIC");
        cluster = System.getenv("CLUSTER");
        poll = Long.valueOf(System.getenv("POLL"));
        SEC = Long.valueOf(System.getenv("SEC"));
        choice = System.getenv("CHOICE");
        CONSUMER_GROUP = System.getenv("CONSUMER_GROUP");
        allowableLag = Long.parseLong(System.getenv("AllOWABLE_LAG"));
        mode = System.getenv("Mode");
        BOOTSTRAP_SERVERS = System.getenv("BOOTSTRAP_SERVERS");
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        admin = AdminClient.create(props);

        lastDecision= Instant.now().minus(1, ChronoUnit.DAYS);


        while (true) {
            log.info("=================Starting new iteration Iteration===============");
            log.info("Iteration {}", iteration);

            //get committed  offsets
            Map<TopicPartition, OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(CONSUMER_GROUP)
                            .partitionsToOffsetAndMetadata().get();
            numberOfPartitions = offsets.size();
            Map<TopicPartition, OffsetSpec> requestLatestOffsets = new HashMap<>();
            //initialize consumer to lag to 0
            for (TopicPartition tp : offsets.keySet()) {
                requestLatestOffsets.put(tp, OffsetSpec.latest());
                partitionToLag.put(tp, 0L);
            }
            //blocking call to query latest offset
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets =
                    admin.listOffsets(requestLatestOffsets).all().get();
            //////////////////////////////////////////////////////////////////////
            // Partition Statistics
            /////////////////////////////////////////////////////////////////////
            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : offsets.entrySet()) {
                long committedOffset = e.getValue().offset();
                long latestOffset = latestOffsets.get(e.getKey()).offset();
                long lag = latestOffset - committedOffset;

                if (!firstIteration) {
                    previousPartitionToCommittedOffset.put(e.getKey(), currentPartitionToCommittedOffset.get(e.getKey()));
                    previousPartitionToLastOffset.put(e.getKey(), currentPartitionToLastOffset.get(e.getKey()));
                }
                currentPartitionToCommittedOffset.put(e.getKey(), committedOffset);
                currentPartitionToLastOffset.put(e.getKey(), latestOffset);
                partitionToLag.put(e.getKey(), lag);
            }
            //////////////////////////////////////////////////////////////////////
            // consumer group statistics
            /////////////////////////////////////////////////////////////////////

            //Long lag = 0L;
            //get information on consumer groups, their partitions and their members

            DescribeConsumerGroupsResult describeConsumerGroupsResult =
                    admin.describeConsumerGroups(Collections.singletonList(Scaler.CONSUMER_GROUP));
            KafkaFuture<Map<String, ConsumerGroupDescription>> futureOfDescribeConsumerGroupsResult =
                    describeConsumerGroupsResult.all();


            //Map<String, ConsumerGroupDescription> consumerGroupDescriptionMap;
            consumerGroupDescriptionMap = futureOfDescribeConsumerGroupsResult.get();
            log.info("The consumer group {} is in state {}", Scaler.CONSUMER_GROUP,
                    consumerGroupDescriptionMap.get(Scaler.CONSUMER_GROUP).state().toString());


            // if a particular consumer is removed as a result of scaling decision
            // remove it from the map cache where applicable
            Set<MemberDescription> previousConsumers = new HashSet<MemberDescription>(consumerToLag.keySet());
            for (MemberDescription md : previousConsumers) {
                //log.info("Member Description client id {}, consumer id {}, host {}", md.clientId(), md.consumerId(), md.host());
                if (consumerGroupDescriptionMap.get(Scaler.CONSUMER_GROUP).members().contains(md)) {
                    if(!firstIteration) {
                        log.info("Calling the consumer {} for its consumption rate ", md.host());
                        float rate = callForConsumptionRate(md.host());
                        maxConsumptionRatePerConsumer.put(md, rate);
                        continue;
                    }
                }
                consumerToLag.remove(md);
                maxConsumptionRatePerConsumer.remove(md);
            }
            //Compute lag per consumer
            Long consumerLag = 0L;
            for (MemberDescription memberDescription : consumerGroupDescriptionMap.get(Scaler.CONSUMER_GROUP).members()) {
                MemberAssignment memberAssignment = memberDescription.assignment();
                for (TopicPartition tp : memberAssignment.topicPartitions()) {
                    consumerLag += partitionToLag.get(tp);
                }
                consumerToLag.put(memberDescription, consumerLag);
                consumerLag = 0L;
            }
            if(!firstIteration) {
                binPackAndScale();
                if(mode.equalsIgnoreCase("proactive")){
                    //proactive(consumerGroupDescriptionMap);
                } else if (mode.equalsIgnoreCase("reactive")) {
                    //reactiveLag(consumerGroupDescriptionMap);
                } else if (mode.equalsIgnoreCase("eagerlazy")) {
                    //eagerlazy(consumerGroupDescriptionMap);
                }

            } else {
                //metadataConsumer = prepareConsumer();
                firstIteration = false;
            }

            log.info("sleeping for  {} secs", sleep);
            Thread.sleep(sleep);
            iteration++;
        }
    }

    private static void binPackAndScale() {
        if((Duration.between( lastDecision, Instant.now()).toMinutes()<1)) {
            log.info("time elpased since last decision is {}",
                    Duration.between(lastDecision, Instant.now()).toSeconds());
            return;
            //cooldown period of 1 min
        }

        log.info("Inside binPackAndScale ");

        List<Consumer> consumers = new ArrayList<>();
        List<Partition> partitions = new ArrayList<>();
        int consumerCount=0;
        long averageConsumptionRate=0;

        // take the average consumptiopn rate directly out of the
        for (MemberDescription md : maxConsumptionRatePerConsumer.keySet()){
            consumerCount++;
            consumers.add(new Consumer(String.valueOf(consumerCount),
                    (long)(Math.floor((maxConsumptionRatePerConsumer.get(md)* 5.0)))));
            //get the first consumer to see wether it fits the lags
            averageConsumptionRate =  (long)(Math.floor((maxConsumptionRatePerConsumer.get(md)* 5.0)));
            break;
        }
        //construct the partion objects with their lags
        for (TopicPartition partition : partitionToLag.keySet()) {
            partitions.add(new Partition( partition.partition(), partitionToLag.get(partition)));
        }

        Collections.sort(partitions, Collections.reverseOrder());
        /*log.info("sorted partitions");
        for(Partition p : partitions){
            log.info("partition {} has the following lag {}", p.getId(), p.getLag() );
        }*/
        for(Consumer cons: consumers){
            log.info("consumer {} has the following initial capacity {}", cons.getId(), cons.getCapacity());
        }

        log.info("averageConsumptionRate = {}", averageConsumptionRate);



        Consumer consumer = null;
        for (Partition partition : partitions) {
            for (Consumer cons : consumers) {
                if (cons.getRemainingSize() >= partition.getLag()) {
                    cons.assignPartition(partition);
                    // we are done with this partition, go to next
                    break;
                }
                //we have iterated over all the consumers hoping to fit that partition, but nope
                //we shall create a new consumer i.e., scale up
                if (cons == consumers.get(consumers.size() - 1)) {
                    consumerCount++;
                    consumer = new Consumer(String.valueOf(consumerCount), averageConsumptionRate);
                    consumer.assignPartition(partition);
                }
            }
            if (consumer != null) {
                consumers.add(consumer);
                consumer = null;
            }
        }

        log.info("Currently we need this consumers {}", consumers.size()!=0?consumers.size():1 );
        log.info("Calling code to scale");
        scaleAsPerBinPack(consumers.size());
        lastDecision = Instant.now();

    }


    private static void scaleAsPerBinPack(int neededsize) {
         //same number of consumers but different different assignment
        log.info("inside the function scale as per the bin pack");
        log.info("We currently need the following consumers {}", neededsize);

        int currentsize = consumerGroupDescriptionMap.get(Scaler.CONSUMER_GROUP).members().size();
        log.info("Currently we have this number of consumers {}", currentsize);

        int replicasForscale = neededsize - currentsize;
        // but is the assignmenet the same
        if(replicasForscale == 0) {
            log.info("No need to autoscale");
        }
        else if(replicasForscale > 0){
            log.info("We have to upscale by {}", replicasForscale);
        }
        else {
            log.info("We have to downscale by {}", Math.abs(replicasForscale));
        }


    }



        private static float callForConsumptionRate(String host) {
        ManagedChannel managedChannel = ManagedChannelBuilder.forAddress(host.substring(1), 5002)
                .usePlaintext()
                .build();
        RateServiceGrpc.RateServiceBlockingStub rateServiceBlockingStub
                = RateServiceGrpc.newBlockingStub(managedChannel);
        RateRequest rateRequest = RateRequest.newBuilder().setRate("Give me your rate")
                .build();
        log.info("connected to server {}", host);
        RateResponse rateResponse = rateServiceBlockingStub.consumptionRate(rateRequest);
        log.info("Received response on the rate: "+ rateResponse.getRate());
        managedChannel.shutdown();
        return rateResponse.getRate();
    }


    static void reactiveLag (Map<String, ConsumerGroupDescription> consumerGroupDescriptionMap) throws InterruptedException {
        float totalConsumptionRate = 0;
        float totalArrivalRate = 0;
        long totallag = 0;
        int size = consumerGroupDescriptionMap.get(Scaler.CONSUMER_GROUP).members().size();
        log.info("Currently we have this number of consumers {}", size);
        for (MemberDescription memberDescription : consumerGroupDescriptionMap.get(Scaler.CONSUMER_GROUP).members()) {
            long totalpoff = 0;
            long totalcoff = 0;
            long totalepoff = 0;
            long totalecoff = 0;
            for (TopicPartition tp : memberDescription.assignment().topicPartitions()) {
                totalpoff += previousPartitionToCommittedOffset.get(tp);
                totalcoff += currentPartitionToCommittedOffset.get(tp);
                totalepoff += previousPartitionToLastOffset.get(tp);
                totalecoff += currentPartitionToLastOffset.get(tp);
            }

            float consumptionRatePerConsumer;
            float arrivalRatePerConsumer;

            consumptionRatePerConsumer = (float) (totalcoff - totalpoff) / sleep;
            arrivalRatePerConsumer = (float) (totalecoff - totalepoff) / sleep;


            totalConsumptionRate += consumptionRatePerConsumer;
            totalArrivalRate += arrivalRatePerConsumer;
            totallag += consumerToLag.get(memberDescription);
        }
        log.info("totalArrivalRate {}, totalconsumptionRate {}, totallag {}",
                totalArrivalRate*1000, totalConsumptionRate*1000, totallag);
        log.info("shall we up scale totalArrivalrate {}, max  consumption rate {}",
                totalArrivalRate *1000, (size *poll)/(float)SEC);

        if ((totalArrivalRate * 1000) > ((size *poll )/(float)SEC) )  {
            if (size < numberOfPartitions) {
                log.info("Consumers are less than nb partition we can scale");
                try (final KubernetesClient k8s = new DefaultKubernetesClient()) {
                    ServiceAccount fabric8 = new ServiceAccountBuilder().withNewMetadata().withName("fabric8").endMetadata().build();
                    k8s.serviceAccounts().inNamespace("default").createOrReplace(fabric8);
                    k8s.apps().deployments().inNamespace("default").withName("cons1persec").scale(size + 1);
                    log.info("Since arrival rate {} is greater than  maximum consumption rate  " +
                                    "(size*poll)  ,  I up scaled  by one {}",
                            totalArrivalRate * 1000, (size *poll)/(float)SEC);
                }
            } else {
                log.info("Consumers are equal to nb partitions we can not scale up anymore");
            }
        }
        else if ((totalArrivalRate *1000)  < (((size-1) *poll)/(float)SEC) && totalArrivalRate>0 && totallag < allowableLag )  {
            try (final KubernetesClient k8s = new DefaultKubernetesClient()) {
                ServiceAccount fabric8 = new ServiceAccountBuilder().withNewMetadata().withName("fabric8").endMetadata().build();
                k8s.serviceAccounts().inNamespace("default").createOrReplace(fabric8);
                int replicas = k8s.apps().deployments().inNamespace("default").withName("cons1persec").get().getSpec().getReplicas();
                if (replicas > 1) {
                    k8s.apps().deployments().inNamespace("default").withName("cons1persec").scale(replicas - 1);
                    log.info("since arrival rate {} is lower than max consumption rate " +
                                    " with size -1 , I down scaled  by one {}",
                            totalArrivalRate * 1000,
                            ((size-1) *poll )/(float)SEC);
                } else {
                    log.info("Not going to  down scale since replicas already one");
                }
            }
        }
        log.info("No scale is needed");
        log.info("quitting scale decision");
    }

}
