package app;

import app.types.Temperature;
import app.util.CLI;
import app.util.WordCountData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.connector.file.sink.FileSink;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineFormat;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.DefaultRollingPolicy;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

public class WordCount {

  // *************************************************************************
  // PROGRAM
  // *************************************************************************

  public static void main(String[] args) throws Exception {
    final CLI params = CLI.fromArgs(args);

    // Create the execution environment. This is the main entrypoint
    // to building a Flink application.
    final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

    // Apache Flink’s unified approach to stream and batch processing means that a DataStream
    // application executed over bounded input will produce the same final results regardless
    // of the configured execution mode. It is important to note what final means here: a job
    // executing in STREAMING mode might produce incremental updates (think upserts in
    // a database) while in BATCH mode, it would only produce one final result at the end. The
    // final result will be the same if interpreted correctly, but getting there can be
    // different.
    //
    // The “classic” execution behavior of the DataStream API is called STREAMING execution
    // mode. Applications should use streaming execution for unbounded jobs that require
    // continuous incremental processing and are expected to stay online indefinitely.
    //
    // By enabling BATCH execution, we allow Flink to apply additional optimizations that we
    // can only do when we know that our input is bounded. For example, different
    // join/aggregation strategies can be used, in addition to a different shuffle
    // implementation that allows more efficient task scheduling and failure recovery behavior.
    //
    // By setting the runtime mode to AUTOMATIC, Flink will choose BATCH if all sources
    // are bounded and otherwise STREAMING.
    env.setRuntimeMode(params.getExecutionMode());

    // This optional step makes the input parameters
    // available in the Flink UI.
    env.getConfig().setGlobalJobParameters(params);

    DataStream<String> text;
    if (params.getInputs().isPresent()) {
      // Create a new file source that will read files from a given set of directories.
      // Each file will be processed as plain text and split based on newlines.
      FileSource.FileSourceBuilder<String> builder =
        FileSource.forRecordStreamFormat(
          new TextLineFormat(), params.getInputs().get());

      // If a discovery interval is provided, the source will
      // continuously watch the given directories for new files.
      params.getDiscoveryInterval().ifPresent(builder::monitorContinuously);

      text = env.fromSource(builder.build(), WatermarkStrategy.noWatermarks(), "file-input");
    } else {
      text = env.fromElements(WordCountData.WORDS).name("in-memory-input");
    }

    DataStream<Tuple2<Long, Integer>> counts =
      // The text lines read from the source are split into words
      // using a user-defined function. The tokenizer, implemented below,
      // will output each word as a (2-tuple) containing (word, 1)
      text.process(new TempDataSource())
        .name("temperature")
        // keyBy groups tuples based on the "0" field, the word.
        // Using a keyBy allows performing aggregations and other
        // stateful transformations over data on a per-key basis.
        // This is similar to a GROUP BY clause in a SQL query.
        .keyBy(value -> value.f0)
        // For each key, we perform a simple sum of the "1" field, the count.
        // If the input data stream is bounded, sum will output a final count for
        // each word. If it is unbounded, it will continuously output updates
        // each time it sees a new instance of each word in the stream.
        .sum(1)
        .name("counter");

    if (params.getOutput().isPresent()) {
      // Given an output directory, Flink will write the results to a file
      // using a simple string encoding. In a production environment, this might
      // be something more structured like CSV, Avro, JSON, or Parquet.
      counts.sinkTo(
          FileSink.<Tuple2<Long, Integer>>forRowFormat(
              params.getOutput().get(), new SimpleStringEncoder<>())
            .withRollingPolicy(
              DefaultRollingPolicy.builder()
                .withMaxPartSize(MemorySize.ofMebiBytes(1).getBytes())
                .withRolloverInterval(Duration.ofSeconds(10).getSeconds())
                .build())
            .build())
        .name("file-sink");
    } else {
      counts.print().name("print-sink");
    }

    // Apache Flink applications are composed lazily. Calling execute
    // submits the Job and begins processing.
    env.execute("WordCount");
  }

  // *************************************************************************
  // USER FUNCTIONS
  // *************************************************************************

  /**
   * Implements the string tokenizer that splits sentences into words as a user-defined
   * FlatMapFunction. The function takes a line (String) and splits it into multiple pairs in the
   * form of "(word,1)" ({@code Tuple2<String, Integer>}).
   *
   * topic,key,currenttime,temp_last_changed_time,fahrenheit,celcius,partition,offset
   */
  public static final class Tokenizer
    implements FlatMapFunction<String, Tuple2<Long, Integer>> {

    @Override
    public void flatMap(String value, Collector<Tuple2<Long, Integer>> out) {
      // normalize and split the line
      String[] tokens = value.toLowerCase().split("\\W+");

      // emit the pairs
      for (String token : tokens) {
        if (token.length() > 0) {
          out.collect(new Tuple2<>(1L, 1));
        }
      }
    }
  }

  private static class TempDataSource extends RichParallelSourceFunction<Tuple2<Long, Long>> {

    private volatile boolean running = true;

    @Override
    public void run(SourceContext<Tuple2<Long, Long>> ctx) throws Exception {

      final long startTime = System.currentTimeMillis();

      final long numElements = 20000000;
      final long numKeys = 10000;
      long val = 1L;
      long count = 0L;

      while (running && count < numElements) {
        count++;
        ctx.collect(new Tuple2<>(val++, 1L));

        if (val > numKeys) {
          val = 1L;
        }
      }

      final long endTime = System.currentTimeMillis();
      System.out.println(
        "Took " + (endTime - startTime) + " msecs for " + numElements + " values");
    }

    @Override
    public void cancel() {
      running = false;
    }
  }

  public static final class MapToTemperature implements MapFunction<String, Temperature> {

    @Override
    public Temperature map(String value) throws Exception {
      ObjectMapper objectMapper = new ObjectMapper();

      Temperature temperature = new ObjectMapper().readValue(value, Temperature.class);

      return temperature;
    }
  }

}