/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.benchmark.ingestion.sink;

import com.netease.arctic.benchmark.ingestion.BaseCatalogSync;
import com.netease.arctic.benchmark.ingestion.SyncDbFunction;
import com.netease.arctic.benchmark.ingestion.params.catalog.CatalogParams;
import com.netease.arctic.benchmark.ingestion.params.database.BaseParameters;
import com.netease.arctic.benchmark.ingestion.params.database.SyncDBParams;
import com.netease.arctic.benchmark.ingestion.params.table.HudiParameters;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.catalog.CatalogDatabaseImpl;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.ResolvedCatalogTable;
import org.apache.flink.table.catalog.exceptions.DatabaseAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotExistException;
import org.apache.flink.table.catalog.exceptions.TableAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.configuration.FlinkOptions;
import org.apache.hudi.hive.NonPartitionedExtractor;
import org.apache.hudi.sink.utils.Pipelines;
import org.apache.hudi.util.AvroSchemaConverter;
import org.apache.hudi.util.StreamerUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hudi synchronize implementation of {@link BaseCatalogSync}，which customised operations for
 * building tables
 */
public class HudiCatalogSync extends BaseCatalogSync {

  private final HudiParameters hudiParameters;

  public HudiCatalogSync(BaseParameters baseParameters, HudiParameters hudiParameters) {
    super(baseParameters);
    this.hudiParameters = hudiParameters;
  }

  @Override
  public void createTable(Catalog catalog, String dbName,
      List<Tuple2<ObjectPath, ResolvedCatalogTable>> pathAndTable) {
    Catalog hudi = catalog;
    if (!hudi.databaseExists(dbName)) {
      try {
        hudi.createDatabase(dbName, new CatalogDatabaseImpl(new HashMap<>(), "new db"), false);
      } catch (DatabaseAlreadyExistException e) {
        e.printStackTrace();
      }
    }

    final Map<String, String> options = new HashMap<>();
    final String HIVE_META_STORE_URI = hudiParameters.getHiveMetastoreUri();
    boolean isHiveSync = hudiParameters.getHiveSyncEnable();
    if (isHiveSync) {
      options.put("hive_sync.metastore.uris", HIVE_META_STORE_URI);
    }

    pathAndTable.forEach(e -> {
      try {
        fillHudiTableOptions(options, isHiveSync, dbName, e.f0.getObjectName());
        ObjectPath objectPath = new ObjectPath(dbName, e.f0.getObjectName());

        if (hudi.tableExists(objectPath)) {
          hudi.dropTable(objectPath, true);
        }
        hudi.createTable(objectPath,
            new ResolvedCatalogTable(e.f1.copy(options), e.f1.getResolvedSchema()), false);
      } catch (TableAlreadyExistException ex) {
        ex.printStackTrace();
      } catch (DatabaseNotExistException ex) {
        ex.printStackTrace();
      } catch (TableNotExistException ex) {
        throw new RuntimeException(ex);
      }
    });
  }

  @Override
  public void insertData(StreamTableEnvironment tableEnv, SingleOutputStreamOperator<Void> process,
      CatalogParams sourceCatalogParams, CatalogParams destCatalogParams,
      List<Tuple2<ObjectPath, ResolvedCatalogTable>> s) {
    int defaultParallelism = HudiParameters.DEFAULT_PARALLELISM;

    SyncDbFunction.getParamsList(sourceCatalogParams.getDatabaseName(), s).forEach(p -> {
      DataStream<RowData> dataStream = process.getSideOutput(p.getTag());
      Configuration conf = buildConfiguration(p, destCatalogParams);
      RowType rowType = (RowType) AvroSchemaConverter
          .convertToDataType(StreamerUtil.getSourceSchema(conf)).getLogicalType();

      DataStream<HoodieRecord> hoodieRecordDataStream =
          Pipelines.bootstrap(conf, rowType, defaultParallelism, dataStream);
      DataStream<Object> pipeline =
          Pipelines.hoodieStreamWrite(conf, defaultParallelism, hoodieRecordDataStream);
      Pipelines.compact(conf, pipeline);
    });
  }

  private void fillHudiTableOptions(Map<String, String> options, boolean isHiveSync, String dbName,
      String tableName) {
    if (isHiveSync) {
      options.put("hive_sync.enable", "true");
      options.put("hive_sync.mode", "hms");
      options.put("hive_sync.db", dbName);
      options.put("hive_sync.table", tableName);
      options.put("hive_sync.support_timestamp", "true");
    }
    options.put("table.type", hudiParameters.getTableType());
  }

  private Configuration buildConfiguration(SyncDBParams syncDBParams,
      CatalogParams destCatalogParams) {
    Configuration conf = new Configuration();

    final String HIVE_META_STORE_URI = hudiParameters.getHiveMetastoreUri();
    boolean isHiveSync = hudiParameters.getHiveSyncEnable();

    // table message options
    conf.setString(FlinkOptions.TABLE_NAME, syncDBParams.getTable());
    conf.setString(FlinkOptions.PATH, hudiParameters.getCatalogPath() + "/" +
        destCatalogParams.getDatabaseName() + "/" + syncDBParams.getTable());
    conf.setString(FlinkOptions.TABLE_TYPE, hudiParameters.getTableType());
    String inferredSchema =
        AvroSchemaConverter.convertToSchema(syncDBParams.getRowType()).toString();
    conf.setString(FlinkOptions.SOURCE_AVRO_SCHEMA, inferredSchema);

    conf.setString(FlinkOptions.INDEX_TYPE, "BUCKET");
    conf.setInteger(FlinkOptions.BUCKET_INDEX_NUM_BUCKETS,
        HudiParameters.DEFAULT_BUCKET_INDEX_NUM_BUCKETS);
    conf.setString(FlinkOptions.INDEX_KEY_FIELD,
        syncDBParams.getSchema().getPrimaryKey()
            .orElseThrow(() -> new RuntimeException(syncDBParams.getTable() + "no pk "))
            .getColumnNames().get(0));
    conf.setString(FlinkOptions.RECORD_KEY_FIELD,
        syncDBParams.getSchema().getPrimaryKey()
            .orElseThrow(() -> new RuntimeException(syncDBParams.getTable() + "no pk "))
            .getColumnNames().get(0));
    conf.setString(FlinkOptions.PRECOMBINE_FIELD, FlinkOptions.NO_PRE_COMBINE);

    //partition options
    conf.setString(FlinkOptions.HIVE_SYNC_PARTITION_EXTRACTOR_CLASS_NAME, NonPartitionedExtractor.class.getCanonicalName());

    // read and write task set
    conf.setInteger(FlinkOptions.READ_TASKS, hudiParameters.getReadTasks());
    conf.setInteger(FlinkOptions.BUCKET_ASSIGN_TASKS, HudiParameters.DEFAULT_BUCKET_ASSIGN_TASK);
    conf.setInteger(FlinkOptions.WRITE_TASKS, hudiParameters.getWriteTasks());

    // compaction options
    conf.setString(FlinkOptions.COMPACTION_TRIGGER_STRATEGY, FlinkOptions.NUM_OR_TIME);
    conf.setInteger(FlinkOptions.COMPACTION_DELTA_COMMITS, 1);
    conf.setInteger(FlinkOptions.COMPACTION_DELTA_SECONDS, 120);
    conf.setInteger(FlinkOptions.COMPACTION_TASKS, hudiParameters.getCompactionTasks());

    // hive sync options
    conf.setBoolean(FlinkOptions.HIVE_SYNC_ENABLED, hudiParameters.getHiveSyncEnable());

    if (isHiveSync) {
      conf.setString(FlinkOptions.HIVE_SYNC_METASTORE_URIS, HIVE_META_STORE_URI);
      conf.setString(FlinkOptions.HIVE_SYNC_MODE, "hms");
      conf.setString(FlinkOptions.HIVE_SYNC_DB, destCatalogParams.getDatabaseName());
      conf.setString(FlinkOptions.HIVE_SYNC_TABLE, syncDBParams.getTable());
      conf.setBoolean(FlinkOptions.HIVE_SYNC_SUPPORT_TIMESTAMP, true);
    }

    return conf;
  }
}
