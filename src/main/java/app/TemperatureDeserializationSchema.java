package app;

import app.types.Temperature;
import org.apache.flink.formats.avro.AvroDeserializationSchema;

public class TemperatureDeserializationSchema extends AvroDeserializationSchema<Temperature> {

}
