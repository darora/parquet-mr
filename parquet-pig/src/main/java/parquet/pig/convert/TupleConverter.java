/**
 * Copyright 2012 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package parquet.pig.convert;

import java.util.ArrayList;
import java.util.List;

import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.NonSpillableDataBag;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.logicalLayer.schema.Schema.FieldSchema;

import parquet.column.Dictionary;
import parquet.io.ParquetDecodingException;
import parquet.io.api.Binary;
import parquet.io.api.Converter;
import parquet.io.api.GroupConverter;
import parquet.io.api.PrimitiveConverter;
import parquet.pig.TupleConversionException;
import parquet.schema.GroupType;
import parquet.schema.OriginalType;
import parquet.schema.PrimitiveType;
import parquet.schema.Type;
import parquet.schema.Type.Repetition;

/**
 * converts a group into a tuple
 *
 * @author Julien Le Dem
 *
 */

public class TupleConverter extends GroupConverter {

  private static final TupleFactory TF = TupleFactory.getInstance();

  private final int schemaSize;

  protected Tuple currentTuple;
  private final Converter[] converters;

  private final GroupType parquetSchema;

  private final boolean numbersDefaultToZero;

  public TupleConverter(GroupType parquetSchema, Schema pigSchema, boolean numbersDefaultToZero) {
    this.parquetSchema = parquetSchema;
    this.numbersDefaultToZero = numbersDefaultToZero;
    try {
      this.schemaSize = parquetSchema.getFieldCount();
      if (schemaSize != pigSchema.size()) {
        throw new IllegalArgumentException("schema sizes don't match:\n" + parquetSchema + "\n" + pigSchema);
      }
      this.converters = new Converter[this.schemaSize];
      for (int i = 0; i < schemaSize; i++) {
        FieldSchema field = pigSchema.getField(i);
        Type type = parquetSchema.getType(i);
        final int index = i;
        converters[i] = newConverter(field, type, new ParentValueContainer() {
          @Override
          void add(Object value) {
            TupleConverter.this.set(index, value);
          }
        }, numbersDefaultToZero);
      }
    } catch (FrontendException e) {
      throw new ParquetDecodingException("can not initialize pig converter from:\n" + parquetSchema + "\n" + pigSchema, e);
    }
  }

  static Converter newConverter(FieldSchema pigField, Type type, final ParentValueContainer parent, boolean numbersDefaultToZero) {
    try {
      switch (pigField.type) {
      case DataType.BAG:
        return new BagConverter(type.asGroupType(), pigField, parent, numbersDefaultToZero);
      case DataType.MAP:
        return new MapConverter(type.asGroupType(), pigField, parent, numbersDefaultToZero);
      case DataType.TUPLE:
        return new TupleConverter(type.asGroupType(), pigField.schema, numbersDefaultToZero) {
          @Override
          public void end() {
            super.end();
            parent.add(this.currentTuple);
          }
        };
      case DataType.CHARARRAY:
        return new FieldStringConverter(parent);
      case DataType.BYTEARRAY:
        return new FieldByteArrayConverter(parent);
      case DataType.INTEGER:
        return new FieldIntegerConverter(parent);
      case DataType.BOOLEAN:
        return new FieldBooleanConverter(parent);
      case DataType.FLOAT:
        return new FieldFloatConverter(parent);
      case DataType.DOUBLE:
        return new FieldDoubleConverter(parent);
      case DataType.LONG:
        return new FieldLongConverter(parent);
      default:
        throw new TupleConversionException("unsupported pig type: " + pigField);
      }
    } catch (FrontendException e) {
      throw new TupleConversionException("error while preparing converter for:\n" + pigField + "\n" + type, e);
    } catch (RuntimeException e) {
      throw new TupleConversionException("error while preparing converter for:\n" + pigField + "\n" + type, e);
    }
  }

  @Override
  public Converter getConverter(int fieldIndex) {
    return converters[fieldIndex];
  }

  private static final Integer I32_ZERO = Integer.valueOf(0);
  private static final Long I64_ZERO = Long.valueOf(0);
  private static final Float FLOAT_ZERO = Float.valueOf(0);
  private static final Double DOUBLE_ZERO = Double.valueOf(0);

  @Override
  final public void start() {
    currentTuple = TF.newTuple(schemaSize);
    if (numbersDefaultToZero) {
      try {
        int i = 0;
        for (Type field : parquetSchema.getFields()) {
          if (field.isPrimitive() && field.getRepetition() == Repetition.OPTIONAL) {
            PrimitiveType primitiveType = field.asPrimitiveType();
            switch (primitiveType.getPrimitiveTypeName()) {
            case INT32:
              currentTuple.set(i, I32_ZERO);
              break;
            case INT64:
              currentTuple.set(i, I64_ZERO);
              break;
            case FLOAT:
              currentTuple.set(i, FLOAT_ZERO);
              break;
            case DOUBLE:
              currentTuple.set(i, DOUBLE_ZERO);
              break;
            }
          }
          ++ i;
        }
      } catch (ExecException e) {
        throw new RuntimeException(e);
      }
    }
  }

  final void set(int fieldIndex, Object value) {
    try {
      currentTuple.set(fieldIndex, value);
    } catch (ExecException e) {
      throw new TupleConversionException(
          "Could not set " + value +
          " to current tuple " + currentTuple + " at " + fieldIndex, e);
    }
  }

  @Override
  public void end() {
  }

  final public Tuple getCurrentTuple() {
    return currentTuple;
  }

  /**
   * handle string values.
   * In case of dictionary encoding, the strings will be decoded only once.
   * @author Julien Le Dem
   *
   */
  static final class FieldStringConverter extends PrimitiveConverter {

    private final ParentValueContainer parent;

    private String[] dict;

    public FieldStringConverter(ParentValueContainer parent) {
      this.parent = parent;
    }

    @Override
    final public void addBinary(Binary value) {
      parent.add(value.toStringUsingUTF8());
    }

    @Override
    public boolean hasDictionarySupport() {
      return true;
    }

    @Override
    public void setDictionary(Dictionary dictionary) {
      if (this.dict!=null) {
        throw new ParquetDecodingException("dictionary can be set only once");
      }
      dict = new String[dictionary.getMaxId() + 1];
      for (int i = 0; i <= dictionary.getMaxId(); i++) {
        dict[i] = dictionary.decodeToBinary(i).toStringUsingUTF8();
      }
    }

    @Override
    public void addValueFromDictionary(int dictionaryId) {
      parent.add(dict[dictionaryId]);
    }
  }

  /**
   * handles DataByteArrays
   * @author Julien Le Dem
   *
   */
  static final class FieldByteArrayConverter extends PrimitiveConverter {

    private final ParentValueContainer parent;

    public FieldByteArrayConverter(ParentValueContainer parent) {
      this.parent = parent;
    }

    @Override
    final public void addBinary(Binary value) {
      parent.add(new DataByteArray(value.getBytes()));
    }

  }

  /**
   * Handles doubles
   * @author Julien Le Dem
   *
   */
  static final class FieldDoubleConverter extends PrimitiveConverter {

    private final ParentValueContainer parent;

    public FieldDoubleConverter(ParentValueContainer parent) {
      this.parent = parent;
    }

    @Override
    final public void addDouble(double value) {
      parent.add(value);
    }

  }

  /**
   * handles floats
   * @author Julien Le Dem
   *
   */
  static final class FieldFloatConverter extends PrimitiveConverter {

    private final ParentValueContainer parent;

    public FieldFloatConverter(ParentValueContainer parent) {
      this.parent = parent;
    }

    @Override
    final public void addFloat(float value) {
      parent.add(value);
    }

  }

  /**
   * Handles longs
   *
   * @author Julien Le Dem
   *
   */
  static final class FieldLongConverter extends PrimitiveConverter {

    private final ParentValueContainer parent;

    public FieldLongConverter(ParentValueContainer parent) {
      this.parent = parent;
    }

    @Override
    final public void addLong(long value) {
      parent.add(value);
    }

  }

  /**
   * handle integers
   * @author Julien Le Dem
   *
   */
  static final class FieldIntegerConverter extends PrimitiveConverter {

    private final ParentValueContainer parent;

    public FieldIntegerConverter(ParentValueContainer parent) {
      this.parent = parent;
    }

    @Override
    final public void addBoolean(boolean value) {
      parent.add(value ? 1 : 0);
    }

    @Override
    final public void addInt(int value) {
      parent.add(value);
    }

  }

  /**
   * handle booleans
   * @author Julien Le Dem
   *
   */
  static final class FieldBooleanConverter extends PrimitiveConverter {

    private final ParentValueContainer parent;

    public FieldBooleanConverter(ParentValueContainer parent) {
      this.parent = parent;
    }

    @Override
    final public void addBoolean(boolean value) {
      parent.add(value);
    }

    @Override
    final public void addInt(int value) {
      parent.add(value != 0);
    }

  }

  /**
   * Converts groups into bags
   *
   * @author Julien Le Dem
   *
   */
  static class BagConverter extends GroupConverter {

    private final List<Tuple> buffer = new ArrayList<Tuple>();
    private final Converter child;
    private final ParentValueContainer parent;

    BagConverter(GroupType parquetSchema, FieldSchema pigSchema, ParentValueContainer parent, boolean numbersDefaultToZero) throws FrontendException {
      this.parent = parent;
      if (parquetSchema.getFieldCount() != 1) {
        throw new IllegalArgumentException("bags have only one field. " + parquetSchema + " size = " + parquetSchema.getFieldCount());
      }
      Type nestedType = parquetSchema.getType(0);

      ParentValueContainer childsParent;
      FieldSchema pigField;
      if (nestedType.isPrimitive() || nestedType.getOriginalType() == OriginalType.MAP || nestedType.getOriginalType() == OriginalType.LIST) {
        // Pig bags always contain tuples
        // In that case we need to wrap the value in an extra tuple
        childsParent = new ParentValueContainer() {
          @Override
          void add(Object value) {
            buffer.add(TF.newTuple(value));
          }};
        pigField = pigSchema.schema.getField(0).schema.getField(0);
      } else {
        childsParent = new ParentValueContainer() {
          @Override
          void add(Object value) {
            buffer.add((Tuple)value);
          }};
        pigField = pigSchema.schema.getField(0);
      }
      child = newConverter(pigField, nestedType, childsParent, numbersDefaultToZero);
    }

    @Override
    public Converter getConverter(int fieldIndex) {
      if (fieldIndex != 0) {
        throw new IllegalArgumentException("bags have only one field. can't reach " + fieldIndex);
      }
      return child;
    }


    @Override
    final public void start() {
      buffer.clear();
    }

    @Override
    public void end() {
      parent.add(new NonSpillableDataBag(new ArrayList<Tuple>(buffer)));
    }

  }

}
