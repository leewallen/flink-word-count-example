package app;

import app.types.Temperature;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.specific.SpecificRecord;
import org.apache.flink.formats.avro.AvroSerializationSchema;

public class TemperatureSerializationSchema extends AvroSerializationSchema<Temperature> {

  /**
   * Creates an Avro deserialization schema.
   *
   * @param recordClazz class to serialize. Should be one of: {@link
   *                    SpecificRecord}, {@link GenericRecord}.
   * @param schema      writer Avro schema. Should be provided if recordClazz is {@link GenericRecord}
   */
  protected TemperatureSerializationSchema(Class<Temperature> recordClazz, Schema schema) {
    super(recordClazz, schema);
  }
}
