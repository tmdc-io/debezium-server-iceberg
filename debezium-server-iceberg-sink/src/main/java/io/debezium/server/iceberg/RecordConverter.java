/*
 *
 *  * Copyright memiiso Authors.
 *  *
 *  * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 *
 */

package io.debezium.server.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.debezium.DebeziumException;
import io.debezium.server.iceberg.tableoperator.Operation;
import io.debezium.server.iceberg.tableoperator.RecordWrapper;
import org.apache.iceberg.Schema;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.debezium.server.iceberg.IcebergChangeConsumer.keyDeserializer;
import static io.debezium.server.iceberg.IcebergChangeConsumer.valDeserializer;

/**
 * Converts iceberg json event to Iceberg GenericRecord. Extracts event schema and key fields. Converts event schema to Iceberg Schema.
 *
 * @author Ismail Simsek
 */
public class RecordConverter {

  protected static final ObjectMapper mapper = new ObjectMapper();
  protected static final Logger LOGGER = LoggerFactory.getLogger(RecordConverter.class);
  public static final List<String> TS_MS_FIELDS = List.of("__ts_ms", "__source_ts_ms");
  static final boolean eventsAreUnwrapped = IcebergUtil.configIncludesUnwrapSmt();
  protected final String destination;
  protected final byte[] valueData;
  protected final byte[] keyData;
  private JsonNode value;
  private JsonNode key;

  public RecordConverter(String destination, byte[] valueData, byte[] keyData) {
    this.destination = destination;
    this.valueData = valueData;
    this.keyData = keyData;
  }

  public JsonNode key() {
    if (key == null && keyData != null) {
      key = keyDeserializer.deserialize(destination, keyData);
    }

    return key;
  }

  public JsonNode value() {
    if (value == null && valueData != null) {
      value = valDeserializer.deserialize(destination, valueData);
    }

    return value;
  }

  public Long cdcSourceTsMsValue(String cdcSourceTsMsField) {

    final JsonNode element = value().get(cdcSourceTsMsField);
    if (element == null) {
      throw new DebeziumException("Field '" + cdcSourceTsMsField + "' not found in JSON object: " + value());
    }

    try {
      return element.asLong();
    } catch (NumberFormatException e) {
      throw new DebeziumException("Error converting field '" + cdcSourceTsMsField + "' value '" + element + "' to Long: " + e.getMessage(), e);
    }
  }

  public Operation cdcOpValue(String cdcOpField) {
    final String opFieldValue;
    if (value().has(cdcOpField)) {
      opFieldValue = value().get(cdcOpField).asText("c");
    } else if (value().has("ddl") && value().has("databaseName")
        && value().has("tableChanges")) {
      // its "schema change topic" https://debezium.io/documentation/reference/3.0/connectors/mysql.html#mysql-schema-change-topic
      opFieldValue = "c";
    } else {
      opFieldValue = null;
    }

    if (opFieldValue == null) {
      throw new DebeziumException("The value for field `" + cdcOpField + "` is missing. " +
          "This field is required when updating or deleting data, when running in upsert mode."
      );
    }

    if (opFieldValue.equals("u")) {
      return Operation.UPDATE;
    } else if (opFieldValue.equals("d")) {
      return Operation.DELETE;
    } else if (opFieldValue.equals("r")) {
      return Operation.READ;
    } else if (opFieldValue.equals("c")) {
      return Operation.INSERT;
    } else if (opFieldValue.equals("i")) {
      return Operation.INSERT;
    }
    throw new DebeziumException("Unexpected `" + cdcOpField + "=" + opFieldValue + "` operation value received, expecting one of ['u','d','r','c', 'i']");
  }

  public SchemaConverter schemaConverter() {
    try {
      return new SchemaConverter(mapper.readTree(valueData).get("schema"), keyData == null ? null : mapper.readTree(keyData).get("schema"));
    } catch (IOException e) {
      throw new DebeziumException("Failed to get event schema", e);
    }
  }

  public Schema icebergSchema(boolean createIdentifierFields) {
    return schemaConverter().icebergSchema(createIdentifierFields);
  }

  public String destination() {
    return destination;
  }

  public RecordWrapper convertAsAppend(Schema schema) {
    GenericRecord row = convert(schema.asStruct(), value());
    return new RecordWrapper(row, Operation.INSERT);
  }

  public RecordWrapper convert(Schema schema, String cdcOpField) {
    GenericRecord row = convert(schema.asStruct(), value());
    Operation op = cdcOpValue(cdcOpField);
    return new RecordWrapper(row, op);
  }

  private static GenericRecord convert(Types.StructType tableFields, JsonNode data) {
    LOGGER.debug("Processing nested field:{}", tableFields);
    GenericRecord record = GenericRecord.create(tableFields);

    for (Types.NestedField field : tableFields.fields()) {
      // Set value to null if json event don't have the field
      if (data == null || !data.has(field.name()) || data.get(field.name()) == null) {
        record.setField(field.name(), null);
        continue;
      }
      // get the value of the field from json event, map it to iceberg value
      record.setField(field.name(), jsonValToIcebergVal(field, data.get(field.name())));
    }

    return record;
  }

  private static Object jsonValToIcebergVal(Types.NestedField field, JsonNode node) {
    LOGGER.debug("Processing Field:{} Type:{}", field.name(), field.type());
    final Object val;
    switch (field.type().typeId()) {
      case INTEGER: // int 4 bytes
        val = node.isNull() ? null : node.asInt();
        break;
      case LONG: // long 8 bytes
        val = node.isNull() ? null : node.asLong();
        break;
      case FLOAT: // float is represented in 32 bits,
        val = node.isNull() ? null : node.floatValue();
        break;
      case DOUBLE: // double is represented in 64 bits
        val = node.isNull() ? null : node.asDouble();
        break;
      case BOOLEAN:
        val = node.isNull() ? null : node.asBoolean();
        break;
      case STRING:
        // if the node is not a value node (method isValueNode returns false), convert it to string.
        val = node.isValueNode() ? node.asText(null) : node.toString();
        break;
      case UUID:
        val = node.isValueNode() ? UUID.fromString(node.asText(null)) : UUID.fromString(node.toString());
        break;
      case TIMESTAMP:
        if ((node.isLong() || node.isNumber()) && TS_MS_FIELDS.contains(field.name())) {
          val = OffsetDateTime.ofInstant(Instant.ofEpochMilli(node.longValue()), ZoneOffset.UTC);
        } else if (node.isTextual()) {
          val = OffsetDateTime.parse(node.asText());
        } else {
          throw new RuntimeException("Failed to convert timestamp value, field: " + field.name() + " value: " + node);
        }
        break;
      case BINARY:
        try {
          val = node.isNull() ? null : ByteBuffer.wrap(node.binaryValue());
        } catch (IOException e) {
          throw new RuntimeException("Failed to convert binary value to iceberg value, field: " + field.name(), e);
        }
        break;
      case LIST:
        Types.NestedField listItemsType = field.type().asListType().fields().get(0);
        // recursive value mapping when list elements are nested type
        if (listItemsType.type().isNestedType()) {
          ArrayList<Object> listVal = new ArrayList<>();
          node.elements().forEachRemaining(element -> {
            listVal.add(jsonValToIcebergVal(field.type().asListType().fields().get(0), element));
          });
          val = listVal;
          break;
        }

        val = mapper.convertValue(node, ArrayList.class);
        break;
      case MAP:
        Type keyType = field.type().asMapType().keyType();
        Type valType = field.type().asMapType().valueType();
        if (keyType.isPrimitiveType() && valType.isPrimitiveType()) {
          val = mapper.convertValue(node, Map.class);
          break;
        }
        // convert complex/nested map value with recursion
        HashMap<Object, Object> mapVal = new HashMap<>();
        node.fields().forEachRemaining(f -> {
          if (valType.isStructType()) {
            mapVal.put(f.getKey(), convert(valType.asStructType(), f.getValue()));
          } else {
            mapVal.put(f.getKey(), f.getValue());
          }
        });
        val = mapVal;
        break;
      case STRUCT:
        // create it as struct, nested type
        // recursive call to get nested data/record
        val = convert(field.type().asStructType(), node);
        break;
      default:
        // default to String type
        // if the node is not a value node (method isValueNode returns false), convert it to string.
        val = node.isValueNode() ? node.asText(null) : node.toString();
        break;
    }

    return val;
  }


  public static class SchemaConverter {
    private final JsonNode valueSchema;
    private final JsonNode keySchema;

    SchemaConverter(JsonNode valueSchema, JsonNode keySchema) {
      this.valueSchema = valueSchema;
      this.keySchema = keySchema;
    }

    protected JsonNode valueSchema() {
      return valueSchema;
    }

    protected JsonNode keySchema() {
      return keySchema;
    }

    /***
     * converts given debezium filed to iceberg field equivalent. does recursion in case of complex/nested types.
     *
     * @param fieldSchema JsonNode representation of debezium field schema.
     * @param fieldName name of the debezium field
     * @param schemaData keeps information of iceberg schema like fields, nextFieldId and identifier fields
     * @return map entry Key being the last id assigned to the iceberg field, Value being the converted iceberg NestedField.
     */
    private static RecordSchemaData debeziumFieldToIcebergField(JsonNode fieldSchema, String fieldName, RecordSchemaData schemaData, JsonNode keySchemaNode) {
      String fieldType = fieldSchema.get("type").textValue();
      boolean isPkField = !(keySchemaNode == null || keySchemaNode.isNull());
      switch (fieldType) {
        case "struct":
          int rootStructId = schemaData.nextFieldId().getAndIncrement();
          final RecordSchemaData subSchemaData = schemaData.copyKeepIdentifierFieldIdsAndNextFieldId();
          for (JsonNode subFieldSchema : fieldSchema.get("fields")) {
            String subFieldName = subFieldSchema.get("field").textValue();
            JsonNode equivalentNestedKeyField = findNodeFieldByName(subFieldName, keySchemaNode);
            debeziumFieldToIcebergField(subFieldSchema, subFieldName, subSchemaData, equivalentNestedKeyField);
          }
          // create it as struct, nested type
          final Types.NestedField structField = Types.NestedField.of(rootStructId, !isPkField, fieldName, Types.StructType.of(subSchemaData.fields()));
          schemaData.fields().add(structField);
          return schemaData;
        case "map":
          if (isPkField) {
            throw new DebeziumException("Cannot set map field '" + fieldName + "' as a identifier field, map types are not supported as an identifier field!");
          }
          int rootMapId = schemaData.nextFieldId().getAndIncrement();
          int keyFieldId = schemaData.nextFieldId().getAndIncrement();
          int valFieldId = schemaData.nextFieldId().getAndIncrement();
          final RecordSchemaData keySchemaData = schemaData.copyKeepIdentifierFieldIdsAndNextFieldId();
          debeziumFieldToIcebergField(fieldSchema.get("keys"), fieldName + "_key", keySchemaData, null);
          schemaData.nextFieldId().incrementAndGet();
          final RecordSchemaData valSchemaData = schemaData.copyKeepIdentifierFieldIdsAndNextFieldId();
          debeziumFieldToIcebergField(fieldSchema.get("values"), fieldName + "_val", valSchemaData, null);
          final Types.MapType mapField = Types.MapType.ofOptional(keyFieldId, valFieldId, keySchemaData.fields().get(0).type(), valSchemaData.fields().get(0).type());
          schemaData.fields().add(Types.NestedField.optional(rootMapId, fieldName, mapField));
          return schemaData;

        case "array":
          if (isPkField) {
            throw new DebeziumException("Cannot set array field '" + fieldName + "' as a identifier field, array types are not supported as an identifier field!");
          }
          int rootArrayId = schemaData.nextFieldId().getAndIncrement();
          final RecordSchemaData arraySchemaData = schemaData.copyKeepIdentifierFieldIdsAndNextFieldId();
          debeziumFieldToIcebergField(fieldSchema.get("items"), fieldName + "_items", arraySchemaData, null);
          final Types.ListType listField = Types.ListType.ofOptional(schemaData.nextFieldId().getAndIncrement(), arraySchemaData.fields().get(0).type());
          schemaData.fields().add(Types.NestedField.optional(rootArrayId, fieldName, listField));
          return schemaData;
        default:
          // its primitive field
          final Types.NestedField field = Types.NestedField.of(schemaData.nextFieldId().getAndIncrement(), !isPkField, fieldName, icebergPrimitiveField(fieldName, fieldType));
          schemaData.fields().add(field);
          if (isPkField) schemaData.identifierFieldIds().add(field.fieldId());
          return schemaData;
      }
    }

    @Override
    public int hashCode() {
      return Objects.hash(valueSchema, keySchema);
    }

    private static JsonNode getNodeFieldsArray(JsonNode node) {
      if (node != null && !node.isNull() && node.has("fields") && node.get("fields").isArray()) {
        return node.get("fields");
      }

      return mapper.createObjectNode();
    }

    private static JsonNode findNodeFieldByName(String fieldName, JsonNode node) {

      for (JsonNode field : getNodeFieldsArray(node)) {

        if (Objects.equals(field.get("field").textValue(), fieldName)) {
          return field;
        }
      }

      return null;
    }

    /***
     * Converts debezium event fields to iceberg equivalent and returns list of iceberg fields.
     * @param schemaNode
     * @return
     */
    private static RecordSchemaData icebergSchemaFields(JsonNode schemaNode, JsonNode keySchemaNode, RecordSchemaData schemaData) {
      LOGGER.debug("Converting iceberg schema to debezium:{}", schemaNode);
      for (JsonNode field : getNodeFieldsArray(schemaNode)) {
        String fieldName = field.get("field").textValue();
        JsonNode equivalentKeyFieldNode = findNodeFieldByName(fieldName, keySchemaNode);
        debeziumFieldToIcebergField(field, fieldName, schemaData, equivalentKeyFieldNode);
      }

      return schemaData;
    }

    private Schema icebergSchema(boolean createIdentifierFields) {

      if (this.valueSchema.isNull()) {
        throw new RuntimeException("Failed to get schema from debezium event, event schema is null");
      }

      RecordSchemaData schemaData = new RecordSchemaData();
      if (!createIdentifierFields) {
        LOGGER.warn("Creating identifier fields is disabled, creating table without identifier field!");
        icebergSchemaFields(valueSchema, null, schemaData);
      } else if (!eventsAreUnwrapped && keySchema != null) {
        ObjectNode nestedKeySchema = mapper.createObjectNode();
        nestedKeySchema.put("type", "struct");
        nestedKeySchema.putArray("fields").add(((ObjectNode) keySchema).put("field", "after"));
        icebergSchemaFields(valueSchema, nestedKeySchema, schemaData);

        if (!schemaData.identifierFieldIds().isEmpty()) {
          // While Iceberg supports nested key fields, they cannot be set with nested events(unwrapped events, Without event flattening)
          // due to inconsistency in the after and before fields.
          // For insert events, only the `before` field is NULL, while for delete events after field is NULL.
          // This inconsistency prevents using either field as a reliable key.
          throw new DebeziumException("Debezium events are unnested, Identifier fields are not supported for unnested events! " +
              "Pleas enable event flattening SMT see: https://debezium.io/documentation/reference/stable/transformations/event-flattening.html " +
              " Or disable identifier field creation `debezium.sink.iceberg.create-identifier-fields=false`");
        }
      } else {
        icebergSchemaFields(valueSchema, keySchema, schemaData);
      }

      if (schemaData.fields().isEmpty()) {
        throw new RuntimeException("Failed to get schema from debezium event, event schema has no fields!");
      }

      // @TODO validate key fields are correctly set!?
      return new Schema(schemaData.fields(), schemaData.identifierFieldIds());

    }

    private static Type.PrimitiveType icebergPrimitiveField(String fieldName, String fieldType) {
      switch (fieldType) {
        case "int8":
        case "int16":
        case "int32": // int 4 bytes
          return Types.IntegerType.get();
        case "int64": // long 8 bytes
          if (TS_MS_FIELDS.contains(fieldName)) {
            return Types.TimestampType.withZone();
          } else {
            return Types.LongType.get();
          }
        case "float8":
        case "float16":
        case "float32": // float is represented in 32 bits,
          return Types.FloatType.get();
        case "double":
        case "float64": // double is represented in 64 bits
          return Types.DoubleType.get();
        case "boolean":
          return Types.BooleanType.get();
        case "string":
          return Types.StringType.get();
        case "uuid":
          return Types.UUIDType.get();
        case "bytes":
          return Types.BinaryType.get();
        default:
          // default to String type
          return Types.StringType.get();
        //throw new RuntimeException("'" + fieldName + "' has "+fieldType+" type, "+fieldType+" not supported!");
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SchemaConverter that = (SchemaConverter) o;
      return Objects.equals(valueSchema, that.valueSchema) && Objects.equals(keySchema, that.keySchema);
    }


  }


}
