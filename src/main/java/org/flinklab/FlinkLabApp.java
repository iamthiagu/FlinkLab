package org.flinklab;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;

import java.io.IOException;
import java.io.InputStream;

public class FlinkLabApp {

    private static final String AVRO_SCHEMA_PATH = "/avro/UserAction.avsc";
    private static Schema userActionSchema;

    public static void main(String[] args) throws Exception {
        try (InputStream inputStream = FlinkLabApp.class.getResourceAsStream(AVRO_SCHEMA_PATH)) {
            if (inputStream == null) {
                throw new RuntimeException("Avro schema file not found: " + AVRO_SCHEMA_PATH);
            }
            Schema.Parser parser = new Schema.Parser();
            userActionSchema = parser.parse(inputStream);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Avro schema: " + AVRO_SCHEMA_PATH, e);
        }

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(5000);

        DataStream<String> input = env.socketTextStream("localhost", 9999);

        input
                .map(line -> {
                    String[] parts = line.split(",");
                    if (parts.length == 3) {
                        GenericRecord record = new GenericData.Record(userActionSchema);
                        record.put("userId", parts[0]);
                        record.put("action", parts[1]);
                        record.put("timestamp", Long.parseLong(parts[2]));
                        return Tuple2.of(parts[0], record);
                    }
                    System.err.println("Malformed input: " + line);
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .keyBy(t -> (String) t.f0)
                .flatMap(new UserActionGenericRecordProcessor())
                .print();

        env.execute("Flink Avro GenericRecord State Application");
    }

    public static class UserActionGenericRecordProcessor extends RichFlatMapFunction<Tuple2<String, GenericRecord>, String> {

        private transient ValueState<GenericRecord> lastUserActionState;

        @Override
        public void open(Configuration parameters) throws Exception {
            ValueStateDescriptor<GenericRecord> descriptor =
                    new ValueStateDescriptor<>(
                            "lastUserActionGenericRecord",
                            TypeInformation.of(GenericRecord.class)
                    );

            StateTtlConfig ttlConfig = StateTtlConfig
                    .newBuilder(Time.minutes(10))
                    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                    .build();
            descriptor.enableTimeToLive(ttlConfig);

            lastUserActionState = getRuntimeContext().getState(descriptor);
        }

        @Override
        public void flatMap(Tuple2<String, GenericRecord> input, Collector<String> out) throws Exception {
            GenericRecord currentUserAction = input.f1;
            GenericRecord storedAction = lastUserActionState.value();

            String currentUserId = (String) currentUserAction.get("userId");
            String currentAction = (String) currentUserAction.get("action");
            Long currentTimestamp = (Long) currentUserAction.get("timestamp");

            if (storedAction == null) {
                out.collect("New user: " + currentUserId +
                        ", First action: " + currentAction +
                        " at " + currentTimestamp);
            } else {
                String previousAction = (String) storedAction.get("action");
                Long previousTimestamp = (Long) storedAction.get("timestamp");

                out.collect("User: " + currentUserId +
                        ", Previous action: " + previousAction +
                        " at " + previousTimestamp +
                        ", Current action: " + currentAction +
                        " at " + currentTimestamp);
            }

            lastUserActionState.update(currentUserAction);
        }
    }
}