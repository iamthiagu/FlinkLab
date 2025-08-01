package com.flinklab;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;


public class FlinkLabApp {

    public static void main(String[] args) throws Exception {
        // 1. Set up the streaming execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Enable checkpointing (important for stateful applications in production)
        env.enableCheckpointing(120000); // Checkpoint every 1 second

        // 2. Define the data source
        // We'll use a socket text stream for interactive testing.
        // You can send input like: "hello world hello flink"
        DataStream<String> text = env.socketTextStream("host.docker.internal", 9999);

        // 3. Apply operators to transform the data

        // Operator 1: FlatMap - Split lines into words and emit (word, 1) tuples
        // This is a stateless transformation.
        DataStream<Tuple2<String, Integer>> words = text
                .flatMap(new Tokenizer()) // This is an operator
                .name("WordTokenizer"); // Assign a name for the Flink UI

        // Operator 2: KeyBy - Partition the stream by word
        // This is a partitioning operator, crucial for stateful operations per key.
        DataStream<Tuple2<String, Integer>> keyedWords = words
                .keyBy(0); // Key by the first element of the tuple (the word)

        // Operator 3: FlatMap (RichFlatMapFunction) - Apply stateful counting
        // This is where the Flink state is used.
        DataStream<Tuple2<String, Integer>> counts = keyedWords
                .flatMap(new StatefulCounter()) // This is an operator with state
                .name("StatefulWordCounter"); // Assign a name for the Flink UI

        // 4. Define the data sink
        // Print the results to the console (stdout of the TaskManager)
        counts.print().name("ResultSink");

        // 5. Execute the Flink job
        env.execute("Flink lab Application");
    }

    /**
     * A simple FlatMapFunction to tokenize input lines into (word, 1) tuples.
     * This is a stateless operator.
     */
    public static final class Tokenizer implements FlatMapFunction<String, Tuple2<String, Integer>> {
        @Override
        public void flatMap(String value, Collector<Tuple2<String, Integer>> out) {
            // Normalize and split the line into words
            String[] words = value.toLowerCase().split("\\W+");

            // Emit the words with a count of 1
            for (String word : words) {
                if (word.length() > 0) {
                    out.collect(new Tuple2<>(word, 1));
                }
            }
        }
    }

    /**
     * A RichFlatMapFunction that uses ValueState to maintain a count for each unique word.
     * This is a stateful operator.
     */
    public static final class StatefulCounter extends RichFlatMapFunction<Tuple2<String, Integer>, Tuple2<String, Integer>> {

        // ValueState to store the current count for the key (word)
        private transient ValueState<Integer> currentCount;

        @Override
        public void open(Configuration parameters) throws Exception {
            // Initialize the ValueStateDescriptor.
            // "wordCount" is the name of the state (used for identification, e.g., in savepoints).
            // TypeInformation.of(Integer.class) specifies the type of data stored in the state.
            ValueStateDescriptor<Integer> descriptor =
                    new ValueStateDescriptor<>(
                            "wordCount", // The state name
                            TypeInformation.of(Integer.class) // The type of data in the state
                    );

            // Get the state handle from the runtime context.
            // This state is scoped to the current key (the word).
            currentCount = getRuntimeContext().getState(descriptor);
        }

        @Override
        public void flatMap(Tuple2<String, Integer> input, Collector<Tuple2<String, Integer>> out) throws Exception {
            // Get the current count from the state. If state is empty (first time seeing this word), it will be null.
            Integer count = currentCount.value();

            // If count is null, initialize it to 0.
            if (count == null) {
                count = 0;
            }

            // Increment the count by the value from the input (which is always 1 in this case)
            count += input.f1;

            // Update the state with the new count
            currentCount.update(count);

            // Emit the word and its updated count
            out.collect(new Tuple2<>(input.f0, count));
        }
    }
}