
/*
 *  Copyright 2017-2018 Adobe.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.adobe.platform.ecosystem.examples.parquet.write;

import java.io.File;
import java.util.*;

import com.adobe.platform.ecosystem.examples.parquet.exception.ParquetIOErrorCode;
import com.adobe.platform.ecosystem.examples.parquet.exception.ParquetIOException;
import com.adobe.platform.ecosystem.examples.parquet.model.ParquetIOField;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.GroupWriteSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.schema.*;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;

import com.adobe.platform.ecosystem.examples.hierarchy.util.HierarchicalBuilderUtil;
import com.adobe.platform.ecosystem.examples.parquet.entity.Node;
import com.adobe.platform.ecosystem.examples.parquet.utility.ParquetIOUtil;

import static org.apache.parquet.schema.Types.*;

public class ParquetIOWriterImpl implements ParquetIOWriter {

    private static final String ROOT_NODE_NAME = "root";

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageType getSchema(List<ParquetIOField> fields) {
        MessageTypeBuilder messageBuilder = Types.buildMessage();
        for(ParquetIOField field : fields) {
            if(field.isPrimitive()) {
                PrimitiveType primitiveType = getPrimitiveTypeField(field);
                messageBuilder.addField(primitiveType);
            } else {
                GroupType groupType = getGroupTypeField(field);
                messageBuilder.addField(groupType);
            }
        }
        return messageBuilder.named("Message");
    }

    private PrimitiveType getPrimitiveTypeField(ParquetIOField field) {
        PrimitiveBuilder<PrimitiveType> primTypeBuilder = null;
        if(field.isRepetetive()) {
            primTypeBuilder =  Types.primitive(field.getType().getParquetPrimitiveType(), Type.Repetition.REPEATED);
        } else {
            primTypeBuilder =  Types.primitive(field.getType().getParquetPrimitiveType(), Type.Repetition.OPTIONAL);
        }

        if(PrimitiveTypeName.BINARY.equals(field.getType().getParquetPrimitiveType())) {
            primTypeBuilder.as(OriginalType.UTF8);
        }
        return primTypeBuilder.named(field.getName());
    }

    private GroupType getGroupTypeField(ParquetIOField field) {
        GroupBuilder<GroupType> groupTypeBuilder;
        if(field.isRepetetive()) {
            groupTypeBuilder = Types.repeatedGroup();
        } else {
            groupTypeBuilder = Types.optionalGroup();
        }

        // Iterate on children and recursively populate groups.
        if(field.getSubFields() != null) {
            for(ParquetIOField subField : field.getSubFields()) {
                if(subField.isPrimitive()) {
                    groupTypeBuilder.addField(getPrimitiveTypeField(subField));
                } else {
                    groupTypeBuilder.addField(getGroupTypeField(subField));
                }
            }
        }
        return groupTypeBuilder.named(field.getName());
    }

    public MessageType getSchema(Map<String, String> columnToTypeMap, String delimiter) {
        Node parentNode = HierarchicalBuilderUtil.buildHierarchy(columnToTypeMap, delimiter);
        String message = "message Message ";
        message = getParquetMessage(parentNode, message, true);
        return MessageTypeParser.parseMessageType(message);
    }

    public File writeParquetFile(MessageType schema, String fileName, List<SimpleGroup> records) throws ParquetIOException {
        File parquetFile = ParquetIOUtil.getLocalFilePath(fileName);
        if (parquetFile.exists()) {
            boolean isDeleted = parquetFile.delete();
            if (!isDeleted) {
                throw new ParquetIOException(ParquetIOErrorCode.PARQUETIO_FILE_DELLETION_EXCEPTION);
            }
        }
        writeToFile(parquetFile, records, schema);
        return parquetFile;
    }

    public File writeSampleParquetFile(MessageType schema, String fileName, int noOfRecords) throws ParquetIOException {
        File parquetFile = ParquetIOUtil.getLocalFilePath(fileName);
        if (parquetFile.exists()) {
            boolean isDeleted = parquetFile.delete();
            if (!isDeleted) {
                throw new ParquetIOException(ParquetIOErrorCode.PARQUETIO_FILE_DELLETION_EXCEPTION);
            }
        }
        List<SimpleGroup> records = this.getRecords(noOfRecords, schema, true);
        writeToFile(parquetFile, records, schema);
        return parquetFile;
    }

    @SuppressWarnings("deprecation")
    private Boolean writeToFile(File f, List<SimpleGroup> records, MessageType schema) {
        Configuration conf = new Configuration();
        conf.set("fs.file.impl", LocalFileSystem.class.getName());
        GroupWriteSupport gws = new GroupWriteSupport();
        GroupWriteSupport.setSchema(schema, conf);

        Boolean isSuccess = false;
        try {
            // TODO: Need to update to start using
            // ParquetWriterBuilder for creating writers.
            ParquetWriter<Group> writer = new ParquetWriter<>(
                    new Path(f.getAbsolutePath()),
                    gws,
                    CompressionCodecName.SNAPPY,
                    128 * 1024 * 1024,
                    ParquetProperties.DEFAULT_PAGE_SIZE,
                    ParquetProperties.DEFAULT_DICTIONARY_PAGE_SIZE,
                    ParquetProperties.DEFAULT_IS_DICTIONARY_ENABLED,
                    false,
                    ParquetProperties.DEFAULT_WRITER_VERSION,
                    conf
            );
            for (SimpleGroup row : records) {
                writer.write(row);
            }
            writer.close();
            isSuccess = true;
        } catch (Exception e) {
            e.printStackTrace();
            return isSuccess;
        }
        return isSuccess;
    }


    private String getParquetMessage(Node parentNode, String message, boolean firstChild) {
        LinkedHashMap<Integer, ArrayList<Node>> children = parentNode.getChildren();

        // executes at leaf node
        String binaryUTF8Convertor = parentNode.getDataType().equals("binary") ? " (UTF8)" : "";

        if (children == null) {
            if (firstChild) {
                message += " {optional " + parentNode.getDataType() + " " + parentNode.getKey() + binaryUTF8Convertor + ";";
                firstChild = false;
            } else
                message += " optional " + parentNode.getDataType() + " " + parentNode.getKey() + binaryUTF8Convertor + ";";
        } else {
            int childIndex = 0;
            for (int index : children.keySet()) {
                childIndex = index;
                break;
            }
            if (children.get(childIndex) != null) {
                if (!parentNode.getKey().equalsIgnoreCase(ROOT_NODE_NAME)) {
                    if (firstChild) {
                        message += " {optional group " + parentNode.getKey();
                        firstChild = false;
                    } else
                        message += " optional group " + parentNode.getKey();
                }

                ArrayList<Node> childrenAtSameLevel = children.get(childIndex);

                for (int i = 0; i < childrenAtSameLevel.size(); i++) {
                    if (i == 0) {
                        firstChild = true;
                    } else {
                        firstChild = false;
                    }
                    message = getParquetMessage(childrenAtSameLevel.get(i), message, firstChild);
                }
                message += " }";
            }
        }

        return message;
    }

    private List<SimpleGroup> getRecords(int noOfRecords, MessageType schema, Boolean isRecordRandom) {
        ArrayList<SimpleGroup> records = new ArrayList<SimpleGroup>();

        for (int i = 0; i < noOfRecords; i++) {
            SimpleGroup root = new SimpleGroup(schema);
            int index = -1;
            if (!isRecordRandom)
                index = 0;
            root = iterateOverSchema(schema, root, index);
            records.add(root);
        }
        return records;
    }

    private SimpleGroup iterateOverSchema(GroupType schema, SimpleGroup root, int index) {
        String[] connectedFieldValue = {"val:z", "val:y", "val:x", "val:a.c", "val:a.b"};
        int noOfFields = schema.getFieldCount();
        for (int p = 0; p < noOfFields; p++) {
            if (schema.getType(p).isPrimitive()) {
                String value = "";
                if (index > -1)
                    value = connectedFieldValue[index + p];
                else
                    value = "hello";
                root.append(schema.getFieldName(p), value);
            } else if (!schema.getType(p).isPrimitive()) {
                int nextIndex = -1;
                if (index > -1) {
                    nextIndex = index + p;
                }
                GroupType groupType = schema.getType(p).asGroupType();
                Group complexGroup = root.addGroup(schema.getFieldName(p));
                iterateOverSchema(groupType, (SimpleGroup) complexGroup, nextIndex);
            }
        }
        return root;
    }
}