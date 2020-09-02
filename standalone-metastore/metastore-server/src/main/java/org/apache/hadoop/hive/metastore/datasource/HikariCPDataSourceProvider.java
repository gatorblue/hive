/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.metastore.datasource;

import com.codahale.metrics.MetricRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.DatabaseProduct;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

import static org.apache.hadoop.hive.metastore.DatabaseProduct.determineDatabaseProduct;

/**
 * DataSourceProvider for the HikariCP connection pool.
 */
public class HikariCPDataSourceProvider implements DataSourceProvider {

  private static final Logger LOG = LoggerFactory.getLogger(HikariCPDataSourceProvider.class);

  static final String HIKARI = "hikaricp";
  private static final String CONNECTION_TIMEOUT_PROPERTY = HIKARI + ".connectionTimeout";

  @Override
  public DataSource create(Configuration hdpConfig) throws SQLException {
    LOG.debug("Creating Hikari connection pool for the MetaStore");

    String driverUrl = DataSourceProvider.getMetastoreJdbcDriverUrl(hdpConfig);
    String user = DataSourceProvider.getMetastoreJdbcUser(hdpConfig);
    String passwd = DataSourceProvider.getMetastoreJdbcPasswd(hdpConfig);

    int maxPoolSize = MetastoreConf.getIntVar(hdpConfig,
        MetastoreConf.ConfVars.CONNECTION_POOLING_MAX_CONNECTIONS);

    Properties properties = replacePrefix(
        DataSourceProvider.getPrefixedProperties(hdpConfig, HIKARI));
    long connectionTimeout = hdpConfig.getLong(CONNECTION_TIMEOUT_PROPERTY, 30000L);

    HikariConfig config;
    try {
      config = new HikariConfig(properties);
    } catch (Exception e) {
      throw new SQLException("Cannot create HikariCP configuration: ", e);
    }
    config.setMaximumPoolSize(maxPoolSize);
    config.setJdbcUrl(driverUrl);
    config.setUsername(user);
    config.setPassword(passwd);

    //https://github.com/brettwooldridge/HikariCP
    config.setConnectionTimeout(connectionTimeout);

    DatabaseProduct dbProduct =  determineDatabaseProduct(driverUrl, null);
    switch (dbProduct.dbType){
      case MYSQL:
        config.setConnectionInitSql("SET @@session.sql_mode=ANSI_QUOTES");
        config.addDataSourceProperty("allowMultiQueries", true);
        config.addDataSourceProperty("rewriteBatchedStatements", true);
        break;
      case POSTGRES:
        config.addDataSourceProperty("reWriteBatchedInserts", true);
        break;
    }
    return new HikariDataSource(initMetrics(config));
  }

  @Override
  public String getPoolingType() {
    return HIKARI;
  }

  private Properties replacePrefix(Properties props) {
    Properties newProps = new Properties();
    props.forEach((key,value) ->
        newProps.put(key.toString().replaceFirst(HIKARI + ".", ""), value));
    return newProps;
  }

  private static HikariConfig initMetrics(final HikariConfig config) {
    final MetricRegistry registry = Metrics.getRegistry();
    if (registry != null) {
      config.setMetricRegistry(registry);
    }
    return config;
  }
}
