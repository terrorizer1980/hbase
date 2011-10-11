/**
 * Copyright 2010 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase.master;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Constructor;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.ClusterStatus;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HConstants.Modify;
import org.apache.hadoop.hbase.HMsg;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HServerAddress;
import org.apache.hadoop.hbase.HServerInfo;
import org.apache.hadoop.hbase.HServerLoad;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.LocalHBaseCluster;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.MiniZooKeeperCluster;
import org.apache.hadoop.hbase.RemoteExceptionHandler;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.MetaScanner;
import org.apache.hadoop.hbase.client.MetaScanner.MetaScannerVisitor;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.ServerConnection;
import org.apache.hadoop.hbase.client.ServerConnectionManager;
import org.apache.hadoop.hbase.executor.HBaseEventHandler.HBaseEventType;
import org.apache.hadoop.hbase.executor.HBaseExecutorService;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.ipc.HBaseRPC;
import org.apache.hadoop.hbase.ipc.HBaseRPCProtocolVersion;
import org.apache.hadoop.hbase.ipc.HBaseServer;
import org.apache.hadoop.hbase.ipc.HMasterInterface;
import org.apache.hadoop.hbase.ipc.HMasterRegionInterface;
import org.apache.hadoop.hbase.ipc.HRegionInterface;
import org.apache.hadoop.hbase.master.metrics.MasterMetrics;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.monitoring.TaskMonitor;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.InfoServer;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Sleeper;
import org.apache.hadoop.hbase.util.VersionInfo;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWrapper;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.net.DNS;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import com.google.common.collect.Lists;

/**
 * HMaster is the "master server" for HBase. An HBase cluster has one active
 * master.  If many masters are started, all compete.  Whichever wins goes on to
 * run the cluster.  All others park themselves in their constructor until
 * master or cluster shutdown or until the active master loses its lease in
 * zookeeper.  Thereafter, all running master jostle to take over master role.
 * @see HMasterInterface
 * @see HMasterRegionInterface
 * @see Watcher
 */
public class HMaster extends Thread implements HMasterInterface,
    HMasterRegionInterface, Watcher {
  // MASTER is name of the webapp and the attribute name used stuffing this
  //instance into web context.
  public static final String MASTER = "master";
  private static final Log LOG = LogFactory.getLog(HMaster.class.getName());
  private static final String LOCALITY_SNAPSHOT_FILE_NAME = "regionLocality-snapshot";

  // We start out with closed flag on.  Its set to off after construction.
  // Use AtomicBoolean rather than plain boolean because we want other threads
  // able to set shutdown flag.  Using AtomicBoolean can pass a reference
  // rather than have them have to know about the hosting Master class.
  final AtomicBoolean closed = new AtomicBoolean(true);
  // TODO: Is this separate flag necessary?
  private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

  private final Configuration conf;
  private final Path rootdir;
  private InfoServer infoServer;
  private final int threadWakeFrequency;
  private final int numRetries;

  // Metrics is set when we call run.
  private final MasterMetrics metrics;

  final Lock splitLogLock = new ReentrantLock();

  // Our zk client.
  private ZooKeeperWrapper zooKeeperWrapper;
  // Watcher for master address and for cluster shutdown.
  private final ZKMasterAddressWatcher zkMasterAddressWatcher;
  // A Sleeper that sleeps for threadWakeFrequency; sleep if nothing todo.
  private final Sleeper sleeper;
  // Keep around for convenience.
  private final FileSystem fs;
  // Is the fileystem ok?
  private volatile boolean fsOk = true;
  // The Path to the old logs dir
  private final Path oldLogDir;

  private final HBaseServer rpcServer;
  private final HServerAddress address;

  private final ServerConnection connection;
  private final ServerManager serverManager;
  private final RegionManager regionManager;

  private long lastFragmentationQuery = -1L;
  private Map<String, Integer> fragmentation = null;
  private final RegionServerOperationQueue regionServerOperationQueue;

  // True if this is the master that started the cluster.
  boolean isClusterStartup;

  private long masterStartupTime = 0;
  private MapWritable preferredRegionToRegionServerMapping = null;
  private long applyPreferredAssignmentPeriod = 0l;
  private long holdRegionForBestLocalityPeriod = 0l;

  // flag set after we become the active master (used for testing)
  private volatile boolean isActiveMaster = false;

  /**
   * Constructor
   * @param conf configuration
   * @throws IOException
   */
  public HMaster(Configuration conf) throws IOException {
    this.conf = conf;

    // Figure out if this is a fresh cluster start. This is done by checking the
    // number of RS ephemeral nodes. RS ephemeral nodes are created only after
    // the primary master has written the address to ZK. So this has to be done
    // before we race to write our address to zookeeper.
    zooKeeperWrapper = ZooKeeperWrapper.createInstance(conf, HMaster.class.getName());
    isClusterStartup = (zooKeeperWrapper.scanRSDirectory().size() == 0);

    // Get my address and create an rpc server instance.  The rpc-server port
    // can be ephemeral...ensure we have the correct info
    HServerAddress a = new HServerAddress(getMyAddress(this.conf));
    this.rpcServer = HBaseRPC.getServer(this, a.getBindAddress(),
      a.getPort(), conf.getInt("hbase.regionserver.handler.count", 10),
      false, conf);
    this.address = new HServerAddress(this.rpcServer.getListenerAddress());

    this.numRetries =  conf.getInt("hbase.client.retries.number", 2);
    this.threadWakeFrequency = conf.getInt(HConstants.THREAD_WAKE_FREQUENCY,
        10 * 1000);

    this.sleeper = new Sleeper(this.threadWakeFrequency, this.closed);
    this.connection = ServerConnectionManager.getConnection(conf);

    // hack! Maps DFSClient => Master for logs.  HDFS made this
    // config param for task trackers, but we can piggyback off of it.
    if (this.conf.get("mapred.task.id") == null) {
      this.conf.set("mapred.task.id", "hb_m_" + this.address.toString());
    }

    // Set filesystem to be that of this.rootdir else we get complaints about
    // mismatched filesystems if hbase.rootdir is hdfs and fs.defaultFS is
    // default localfs.  Presumption is that rootdir is fully-qualified before
    // we get to here with appropriate fs scheme.
    this.rootdir = FSUtils.getRootDir(this.conf);
    // Cover both bases, the old way of setting default fs and the new.
    // We're supposed to run on 0.20 and 0.21 anyways.
    this.conf.set("fs.default.name", this.rootdir.toString());
    this.conf.set("fs.defaultFS", this.rootdir.toString());
    this.fs = FileSystem.get(this.conf);
    checkRootDir(this.rootdir, this.conf, this.fs);

    // Make sure the region servers can archive their old logs
    this.oldLogDir = new Path(this.rootdir, HConstants.HREGION_OLDLOGDIR_NAME);
    if(!this.fs.exists(this.oldLogDir)) {
      this.fs.mkdirs(this.oldLogDir);
    }

    // Get our zookeeper wrapper and then try to write our address to zookeeper.
    // We'll succeed if we are only  master or if we win the race when many
    // masters.  Otherwise we park here inside in writeAddressToZooKeeper.
    // TODO: Bring up the UI to redirect to active Master.
    zooKeeperWrapper.registerListener(this);
    this.zkMasterAddressWatcher =
      new ZKMasterAddressWatcher(this.zooKeeperWrapper, this.shutdownRequested);
    zooKeeperWrapper.registerListener(zkMasterAddressWatcher);

    // if we're a backup master, stall until a primary to writes his address
    if (conf.getBoolean(HConstants.MASTER_TYPE_BACKUP, HConstants.DEFAULT_MASTER_TYPE_BACKUP)) {

      // ephemeral node expiry will be detected between about 40 to 60 seconds;
      // plus add a little extra since only ZK leader can expire nodes, and
      // leader maybe a little  bit delayed in getting info about the pings.
      // Conservatively, just double the time.
      int stallTime = conf.getInt("zookeeper.session.timeout", 60 * 1000) * 2;

      LOG.debug("HMaster started in backup mode. Stall " + stallTime +
          "ms giving primary master a fair chance to be the master...");
      try {
          Thread.sleep(stallTime);
      } catch (InterruptedException e) {
        // interrupted = user wants to kill us.  Don't continue
        throw new IOException("Interrupted waiting for master address");
      }
    }

    this.zkMasterAddressWatcher.writeAddressToZooKeeper(this.address, true);
    isActiveMaster = true;
    this.regionServerOperationQueue =
      new RegionServerOperationQueue(this.conf, this.closed);

    serverManager = new ServerManager(this);


    // Start the unassigned watcher - which will create the unassigned region
    // in ZK. This is needed before RegionManager() constructor tries to assign
    // the root region.
    ZKUnassignedWatcher.start(this.conf, this);
    // start the "close region" executor service
    HBaseEventType.RS2ZK_REGION_CLOSED.startMasterExecutorService(address.toString());
    // start the "open region" executor service
    HBaseEventType.RS2ZK_REGION_OPENED.startMasterExecutorService(address.toString());

    // start the region manager
    regionManager = new RegionManager(this);

    setName(MASTER);
    this.metrics = new MasterMetrics(MASTER, this.serverManager);
    // We're almost open for business
    this.closed.set(false);
    LOG.info("HMaster w/ hbck initialized on " + this.address.toString());
  }

  public long getApplyPreferredAssignmentPeriod() {
    return this.applyPreferredAssignmentPeriod;
  }

  public long getHoldRegionForBestLocalityPeriod() {
    return this.holdRegionForBestLocalityPeriod;
  }

  public long getMasterStartupTime() {
    return this.masterStartupTime;
  }

  public MapWritable getPreferredRegionToRegionServerMapping() {
    return preferredRegionToRegionServerMapping;
  }

  public void clearPreferredRegionToRegionServerMapping() {
    preferredRegionToRegionServerMapping = null;
  }

  /**
   * Returns true if this master process was responsible for starting the
   * cluster.
   */
  public boolean isClusterStartup() {
    return isClusterStartup;
  }

  public void resetClusterStartup() {
    isClusterStartup = false;
  }

  public HServerAddress getHServerAddress() {
    return address;
  }

  /*
   * Get the rootdir.  Make sure its wholesome and exists before returning.
   * @param rd
   * @param conf
   * @param fs
   * @return hbase.rootdir (after checks for existence and bootstrapping if
   * needed populating the directory with necessary bootup files).
   * @throws IOException
   */
  private static Path checkRootDir(final Path rd, final Configuration c,
    final FileSystem fs)
  throws IOException {
    // If FS is in safe mode wait till out of it.
    FSUtils.waitOnSafeMode(c, c.getInt(HConstants.THREAD_WAKE_FREQUENCY,
        10 * 1000));
    // Filesystem is good. Go ahead and check for hbase.rootdir.
    if (!fs.exists(rd)) {
      fs.mkdirs(rd);
      FSUtils.setVersion(fs, rd);
    } else {
      FSUtils.checkVersion(fs, rd, true);
    }
    // Make sure the root region directory exists!
    if (!FSUtils.rootRegionExists(fs, rd)) {
      bootstrap(rd, c);
    }
    return rd;
  }

  private static void bootstrap(final Path rd, final Configuration c)
  throws IOException {
    LOG.info("BOOTSTRAP: creating ROOT and first META regions");
    try {
      // Bootstrapping, make sure blockcache is off.  Else, one will be
      // created here in bootstap and it'll need to be cleaned up.  Better to
      // not make it in first place.  Turn off block caching for bootstrap.
      // Enable after.
      HRegionInfo rootHRI = new HRegionInfo(HRegionInfo.ROOT_REGIONINFO);
      setInfoFamilyCaching(rootHRI, false);
      HRegionInfo metaHRI = new HRegionInfo(HRegionInfo.FIRST_META_REGIONINFO);
      setInfoFamilyCaching(metaHRI, false);
      HRegion root = HRegion.createHRegion(rootHRI, rd, c);
      HRegion meta = HRegion.createHRegion(metaHRI, rd, c);
      setInfoFamilyCaching(rootHRI, true);
      setInfoFamilyCaching(metaHRI, true);
      // Add first region from the META table to the ROOT region.
      HRegion.addRegionToMETA(root, meta);
      root.close();
      root.getLog().closeAndDelete();
      meta.close();
      meta.getLog().closeAndDelete();
    } catch (IOException e) {
      e = RemoteExceptionHandler.checkIOException(e);
      LOG.error("bootstrap", e);
      throw e;
    }
  }

  /*
   * @param hri Set all family block caching to <code>b</code>
   * @param b
   */
  private static void setInfoFamilyCaching(final HRegionInfo hri, final boolean b) {
    for (HColumnDescriptor hcd: hri.getTableDesc().families.values()) {
      if (Bytes.equals(hcd.getName(), HConstants.CATALOG_FAMILY)) {
        hcd.setBlockCacheEnabled(b);
        hcd.setInMemory(b);
      }
    }
  }

  /*
   * @return This masters' address.
   * @throws UnknownHostException
   */
  private static String getMyAddress(final Configuration c)
  throws UnknownHostException {
    // Find out our address up in DNS.
    String s = DNS.getDefaultHost(c.get("hbase.master.dns.interface","default"),
      c.get("hbase.master.dns.nameserver","default"));
    s += ":" + c.get(HConstants.MASTER_PORT,
        Integer.toString(HConstants.DEFAULT_MASTER_PORT));
    return s;
  }

  /**
   * Checks to see if the file system is still accessible.
   * If not, sets closed
   * @return false if file system is not available
   */
  protected boolean checkFileSystem() {
    if (this.fsOk) {
      try {
        FSUtils.checkFileSystemAvailable(this.fs);
      } catch (IOException e) {
        LOG.fatal("Shutting down HBase cluster: file system not available", e);
        this.closed.set(true);
        this.fsOk = false;
      }
    }
    return this.fsOk;
  }

  /** @return HServerAddress of the master server */
  public HServerAddress getMasterAddress() {
    return this.address;
  }

  @Override
  public long getProtocolVersion(String protocol, long clientVersion) {
    return HBaseRPCProtocolVersion.versionID;
  }

  /** @return InfoServer object. Maybe null.*/
  public InfoServer getInfoServer() {
    return this.infoServer;
  }

  /**
   * @return HBase root dir.
   * @throws IOException
   */
  public Path getRootDir() {
    return this.rootdir;
  }

  public int getNumRetries() {
    return this.numRetries;
  }

  /**
   * @return Server metrics
   */
  public MasterMetrics getMetrics() {
    return this.metrics;
  }

  /**
   * @return Return configuration being used by this server.
   */
  public Configuration getConfiguration() {
    return this.conf;
  }

  public ServerManager getServerManager() {
    return this.serverManager;
  }

  public RegionManager getRegionManager() {
    return this.regionManager;
  }

  int getThreadWakeFrequency() {
    return this.threadWakeFrequency;
  }

  FileSystem getFileSystem() {
    return this.fs;
  }

  public AtomicBoolean getShutdownRequested() {
    return this.shutdownRequested;
  }

  AtomicBoolean getClosed() {
    return this.closed;
  }

  public boolean isClosed() {
    return this.closed.get();
  }

  ServerConnection getServerConnection() {
    return this.connection;
  }

  /**
   * Get the ZK wrapper object
   * @return the zookeeper wrapper
   */
  public ZooKeeperWrapper getZooKeeperWrapper() {
    return this.zooKeeperWrapper;
  }

  // These methods are so don't have to pollute RegionManager with ServerManager.
  SortedMap<HServerLoad, Set<String>> getLoadToServers() {
    return this.serverManager.getLoadToServers();
  }

  int numServers() {
    return this.serverManager.numServers();
  }

  public double getAverageLoad() {
    return this.serverManager.getAverageLoad();
  }

  public RegionServerOperationQueue getRegionServerOperationQueue () {
    return this.regionServerOperationQueue;
  }

  /**
   * Get the directory where old logs go
   * @return the dir
   */
  public Path getOldLogDir() {
    return this.oldLogDir;
  }

  /**
   * Add to the passed <code>m</code> servers that are loaded less than
   * <code>l</code>.
   * @param l
   * @param m
   */
  void getLightServers(final HServerLoad l,
      SortedMap<HServerLoad, Set<String>> m) {
    this.serverManager.getLightServers(l, m);
  }

  /** Main processing loop */
  @Override
  public void run() {
    MonitoredTask startupStatus =
      TaskMonitor.get().createStatus("Master startup");
    startupStatus.setDescription("Master startup");
    try {
      joinCluster();
      initPreferredAssignment();
      startupStatus.setStatus("Initializing master service threads");
      startServiceThreads();
      startupStatus.markComplete("Initialization successful");
    } catch (IOException e) {
      LOG.fatal("Unhandled exception. Master quits.", e);
      startupStatus.cleanup();
      return;
    }
    try {
      /* Main processing loop */
      FINISHED: while (!this.closed.get()) {
        // check if we should be shutting down
        if (this.shutdownRequested.get()) {
          // The region servers won't all exit until we stop scanning the
          // meta regions
          this.regionManager.stopScanners();
          if (this.serverManager.numServers() == 0) {
            startShutdown();
            break;
          } else {
            LOG.debug("Waiting on " +
              this.serverManager.getServersToServerInfo().keySet().toString());
          }
        }
        switch (this.regionServerOperationQueue.process()) {
        case FAILED:
            // If FAILED op processing, bad. Exit.
          break FINISHED;
        case REQUEUED_BUT_PROBLEM:
          if (!checkFileSystem())
              // If bad filesystem, exit.
            break FINISHED;
          default:
            // Continue run loop if conditions are PROCESSED, NOOP, REQUEUED
          break;
        }
      }
    } catch (Throwable t) {
      LOG.fatal("Unhandled exception. Starting shutdown.", t);
      startupStatus.cleanup();
      this.closed.set(true);
    }

    startupStatus.cleanup();
    if (!this.shutdownRequested.get()) {  // shutdown not by request
      shutdown();  // indicated that master is shutting down
      startShutdown();  // get started with shutdown: stop scanners etc.
    }

    // Wait for all the remaining region servers to report in.
    this.serverManager.letRegionServersShutdown();

    /*
     * Clean up and close up shop
     */
    if (this.infoServer != null) {
      LOG.info("Stopping infoServer");
      try {
        this.infoServer.stop();
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    }
    this.rpcServer.stop();
    this.regionManager.stop();
    this.zooKeeperWrapper.close();
    HBaseExecutorService.shutdown();
    LOG.info("HMaster main thread exiting");
  }

  private void initPreferredAssignment() {
    // assign the regions based on the region locality in this period of time
    this.applyPreferredAssignmentPeriod =
      conf.getLong("hbase.master.applyPreferredAssignment.period",
          5 * 60 * 1000);

    // disable scanning dfs by setting applyPreferredAssignmentPeriod to 0
    if (applyPreferredAssignmentPeriod > 0) {
      // if a region's best region server hasn't checked in for this much time
      // since master startup, then the master is free to assign this region
      // out to any region server
      this.holdRegionForBestLocalityPeriod =
        conf.getLong("hbase.master.holdRegionForBestLocality.period",
            1 * 60 * 1000);

      // try to get the locality map from disk
      this.preferredRegionToRegionServerMapping = getRegionLocalityFromSnapshot(conf);

      // if we were not successful, let's reevaluate it
      if (this.preferredRegionToRegionServerMapping == null) {
        this.preferredRegionToRegionServerMapping = reevaluateRegionLocality(conf, null, conf.getInt("hbase.master.localityCheck.threadPoolSize", 5));
      }

    }
    // get the start time stamp after scanning the dfs
    masterStartupTime = System.currentTimeMillis();
  }

  public static MapWritable getRegionLocalityFromSnapshot(Configuration conf) {
    String region_assignment_snapshot_dir =
      conf.get("hbase.tmp.dir");
    if (region_assignment_snapshot_dir == null) {
      return null;
    }

    String region_assignment_snapshot =
      region_assignment_snapshot_dir + "/" + LOCALITY_SNAPSHOT_FILE_NAME;

    long refresh_interval =
      conf.getLong("hbase.master.regionLocality.snapshot.validity_time_ms",
          24 * 60 * 60 * 1000);

    File snapshotFile = new File(region_assignment_snapshot);
    try {
      if (!snapshotFile.exists()) {
          LOG.info("preferredRegionToRegionServerMapping snapshot not found. File Path: "
              + region_assignment_snapshot);
          return null;
      }

      long time_elapsed = System.currentTimeMillis() - snapshotFile.lastModified();

      MapWritable regionLocalityMap = null;
      if (time_elapsed < refresh_interval) {
        // load the information from disk
        LOG.debug("Loading preferredRegionToRegionServerMapping from "
            + region_assignment_snapshot);
        regionLocalityMap = new MapWritable();
        regionLocalityMap.readFields(
            new DataInputStream(new FileInputStream(region_assignment_snapshot)));
        return regionLocalityMap;
      }
      else {
        LOG.info("Too long since last evaluated region-assignments. "
            + "Ignoring saved region-assignemnt."
            + " time_elapsed (ms) = " + time_elapsed + " refresh_interval is " + refresh_interval);
      }
      return null;
    }
    catch (IOException e) {
      LOG.error("Error loading the preferredRegionToRegionServerMapping  file: " +
          region_assignment_snapshot +  " from Disk : " + e.toString());
      // do not pause the master's construction
      return null;
    }

  }

  /*
   * Save a copy of the MapWritable regionLocalityMap.
   * The exact location to be stored is fetched from the Configuration given:
   * ${hbase.tmp.dir}/regionLocality-snapshot
   */
  public static MapWritable reevaluateRegionLocality(Configuration conf, String tablename, int poolSize) {
    MapWritable regionLocalityMap = null;

    LOG.debug("Evaluate preferredRegionToRegionServerMapping; expecting pause here");
    try {
      regionLocalityMap = FSUtils
            .getRegionLocalityMappingFromFS(FileSystem.get(conf), FSUtils.getRootDir(conf),
                poolSize,
                conf,
                tablename);
    } catch (Exception e) {
      LOG.error("Got unexpected exception when evaluating " +
          "preferredRegionToRegionServerMapping : " + e.toString());
      // do not pause the master's construction
      return null;
    }

    String tmp_path = conf.get("hbase.tmp.dir");
    if (tmp_path == null) {
      LOG.info("Could not save preferredRegionToRegionServerMapping  " +
          " config paramater hbase.tmp.dir is not set.");
      return regionLocalityMap;
    }

    String region_assignment_snapshot = tmp_path
      + "/" + LOCALITY_SNAPSHOT_FILE_NAME;
    // write the preferredRegionAssignment to disk
    try {
      LOG.info("Saving preferredRegionToRegionServerMapping  " +
          "to file " + region_assignment_snapshot );
      regionLocalityMap.write(new DataOutputStream(
          new FileOutputStream(region_assignment_snapshot)));
    } catch (IOException e) {
        LOG.error("Error saving preferredRegionToRegionServerMapping  " +
            "to file " + region_assignment_snapshot +  " : " + e.toString());
    }
    return regionLocalityMap;
  }


  /*
   * Joins cluster.  Checks to see if this instance of HBase is fresh or the
   * master was started following a failover. In the second case, it inspects
   * the region server directory and gets their regions assignment.
   */
  private void joinCluster() throws IOException  {
    LOG.debug("Checking cluster state...");

    List<HServerAddress> addresses = this.zooKeeperWrapper.scanRSDirectory();
    // Check if this is a fresh start of the cluster
    if (addresses.isEmpty()) {
      LOG.debug("Master fresh start, proceeding with normal startup");
      splitLogAfterStartup();
      return;
    }
    // Failover case.
    LOG.info("Master failover, ZK inspection begins...");
    // only read the rootlocation if it is failover
    HServerAddress rootLocation =
      this.zooKeeperWrapper.readRootRegionLocation();
    boolean isRootRegionAssigned = false;
    Map <byte[], HRegionInfo> assignedRegions =
      new HashMap<byte[], HRegionInfo>();
    // We must:
    // - contact every region server to add them to the regionservers list
    // - get their current regions assignment
    // TODO: Run in parallel?
    for (HServerAddress address : addresses) {
      HRegionInfo[] regions = null;
      try {
        HRegionInterface hri =
          this.connection.getHRegionConnection(address, false);
        HServerInfo info = hri.getHServerInfo();
        LOG.debug("Inspection found server " + info.getServerName());
        this.serverManager.recordNewServer(info, true);
        regions = hri.getRegionsAssignment();
      } catch (IOException e) {
        LOG.error("Failed contacting " + address.toString(), e);
        continue;
      }
      for (HRegionInfo r: regions) {
        if (r.isRootRegion()) {
          this.connection.setRootRegionLocation(new HRegionLocation(r, rootLocation));
          this.regionManager.setRootRegionLocation(rootLocation);
          // Undo the unassign work in the RegionManager constructor
          this.regionManager.removeRegion(r);
          isRootRegionAssigned = true;
        } else if (r.isMetaRegion()) {
          MetaRegion m = new MetaRegion(new HServerAddress(address), r);
          this.regionManager.addMetaRegionToScan(m);
        }
        assignedRegions.put(r.getRegionName(), r);
      }
    }
    LOG.info("Inspection found " + assignedRegions.size() + " regions, " +
      (isRootRegionAssigned ? "with -ROOT-" : "but -ROOT- was MIA"));
    splitLogAfterStartup();
  }

  /*
   * Inspect the log directory to recover any log file without
   * ad active region server.
   */
  private void splitLogAfterStartup() {
    Path logsDirPath =
      new Path(this.rootdir, HConstants.HREGION_LOGDIR_NAME);
    try {
      if (!this.fs.exists(logsDirPath)) return;
    } catch (IOException e) {
      throw new RuntimeException("Could exists for " + logsDirPath, e);
    }
    FileStatus[] logFolders;
    try {
      logFolders = this.fs.listStatus(logsDirPath);
    } catch (IOException e) {
      throw new RuntimeException("Failed listing " + logsDirPath.toString(), e);
    }
    if (logFolders == null || logFolders.length == 0) {
      LOG.debug("No log files to split, proceeding...");
      return;
    }
    for (FileStatus status : logFolders) {
      Path logDir = status.getPath();
      String serverName = logDir.getName();
      LOG.info("Found log folder : " + serverName);
      if(this.serverManager.getServerInfo(serverName) == null) {
        LOG.info("Log folder doesn't belong " +
          "to a known region server, splitting");
        long splitTime = 0, splitSize = 0, splitCount = 0;

        this.splitLogLock.lock();
        try {
          // rename the directory so a rogue RS doesn't create more HLogs
          if (!serverName.endsWith(HConstants.HLOG_SPLITTING_EXT)) {
            Path splitDir = new Path(logDir.getParent(),
                                     logDir.getName()
                                     + HConstants.HLOG_SPLITTING_EXT);
            if (!this.fs.rename(logDir, splitDir)) {
              throw new IOException("Failed fs.rename of " + logDir);
            }
            logDir = splitDir;
            LOG.debug("Renamed region directory: " + splitDir);
          }
          ContentSummary contentSummary = fs.getContentSummary(logDir);
          splitCount = contentSummary.getFileCount();
          splitSize = contentSummary.getSpaceConsumed();
          HLog.splitLog(this.rootdir, logDir, oldLogDir, this.fs, getConfiguration());
          splitTime = HLog.lastSplitTime;
          this.metrics.addSplit(splitTime, splitCount, splitSize );
        } catch (IOException e) {
          LOG.error("Failed splitting " + logDir.toString(), e);
        } finally {
          this.splitLogLock.unlock();
        }
      } else {
        LOG.info("Log folder belongs to an existing region server");
      }
    }
  }

  /*
   * Start up all services. If any of these threads gets an unhandled exception
   * then they just die with a logged message.  This should be fine because
   * in general, we do not expect the master to get such unhandled exceptions
   *  as OOMEs; it should be lightly loaded. See what HRegionServer does if
   *  need to install an unexpected exception handler.
   */
  private void startServiceThreads() {
    try {
      this.regionManager.start();
      // Put up info server.
      int port = this.conf.getInt("hbase.master.info.port", 60010);
      if (port >= 0) {
        String a = this.conf.get("hbase.master.info.bindAddress", "0.0.0.0");
        this.infoServer = new InfoServer(MASTER, a, port, false, conf);
        this.infoServer.setAttribute(MASTER, this);
        this.infoServer.start();
      }
      // Start the server so everything else is running before we start
      // receiving requests.
      this.rpcServer.start();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Started service threads");
      }
    } catch (IOException e) {
      if (e instanceof RemoteException) {
        try {
          e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
        } catch (IOException ex) {
          LOG.warn("thread start", ex);
        }
      }
      // Something happened during startup. Shut things down.
      this.closed.set(true);
      LOG.error("Failed startup", e);
    }
  }

  /*
   * Start shutting down the master
   */
  void startShutdown() {
    this.closed.set(true);
    this.regionManager.stopScanners();
    this.regionServerOperationQueue.shutdown();
    this.serverManager.notifyServers();
  }

  @Override
  public MapWritable regionServerStartup(final HServerInfo serverInfo)
  throws IOException {
    // Set the ip into the passed in serverInfo.  Its ip is more than likely
    // not the ip that the master sees here.  See at end of this method where
    // we pass it back to the regionserver by setting "hbase.regionserver.address"
    String rsAddress = HBaseServer.getRemoteAddress();
    serverInfo.setServerAddress(new HServerAddress(rsAddress,
      serverInfo.getServerAddress().getPort()));
    // Register with server manager
    this.serverManager.regionServerStartup(serverInfo);
    // Send back some config info
    MapWritable mw = createConfigurationSubset();
     mw.put(new Text("hbase.regionserver.address"), new Text(rsAddress));
    return mw;
  }

  /**
   * @return Subset of configuration to pass initializing regionservers: e.g.
   * the filesystem to use and root directory to use.
   */
  protected MapWritable createConfigurationSubset() {
    MapWritable mw = addConfig(new MapWritable(), HConstants.HBASE_DIR);
    return addConfig(mw, "fs.default.name");
  }

  private MapWritable addConfig(final MapWritable mw, final String key) {
    mw.put(new Text(key), new Text(this.conf.get(key)));
    return mw;
  }

  @Override
  public HMsg [] regionServerReport(HServerInfo serverInfo, HMsg msgs[],
    HRegionInfo[] mostLoadedRegions)
  throws IOException {
    return adornRegionServerAnswer(serverInfo,
      this.serverManager.regionServerReport(serverInfo, msgs, mostLoadedRegions));
  }

  /**
   * Override if you'd add messages to return to regionserver <code>hsi</code>
   * or to send an exception.
   * @param msgs Messages to add to
   * @return Messages to return to
   * @throws IOException exceptions that were injected for the region servers
   */
  protected HMsg [] adornRegionServerAnswer(final HServerInfo hsi,
      final HMsg [] msgs) throws IOException {
    return msgs;
  }

  @Override
  public boolean isMasterRunning() {
    return !this.closed.get();
  }

  @Override
  public void shutdown() {
    LOG.info("Cluster shutdown requested. Starting to quiesce servers");
    this.shutdownRequested.set(true);
    this.zooKeeperWrapper.setClusterState(false);
  }

  @Override
  public void createTable(HTableDescriptor desc, byte [][] splitKeys)
  throws IOException {
    if (!isMasterRunning()) {
      throw new MasterNotRunningException();
    }
    HRegionInfo [] newRegions = null;
    if(splitKeys == null || splitKeys.length == 0) {
      newRegions = new HRegionInfo [] { new HRegionInfo(desc, null, null) };
    } else {
      int numRegions = splitKeys.length + 1;
      newRegions = new HRegionInfo[numRegions];
      byte [] startKey = null;
      byte [] endKey = null;
      for(int i=0;i<numRegions;i++) {
        endKey = (i == splitKeys.length) ? null : splitKeys[i];
        newRegions[i] = new HRegionInfo(desc, startKey, endKey);
        startKey = endKey;
      }
    }
    for (int tries = 0; tries < this.numRetries; tries++) {
      try {
        // We can not create a table unless meta regions have already been
        // assigned and scanned.
        if (!this.regionManager.areAllMetaRegionsOnline()) {
          throw new NotAllMetaRegionsOnlineException();
        }
        if (!this.serverManager.canAssignUserRegions()) {
          throw new IOException("not enough servers to create table yet");
        }
        createTable(newRegions);
        LOG.info("created table " + desc.getNameAsString());
        break;
      } catch (TableExistsException e) {
        throw e;
      } catch (IOException e) {
        if (tries == this.numRetries - 1) {
          throw RemoteExceptionHandler.checkIOException(e);
        }
        this.sleeper.sleep();
      }
    }
  }

  private synchronized void createTable(final HRegionInfo [] newRegions)
  throws IOException {
    String tableName = newRegions[0].getTableDesc().getNameAsString();
    // 1. Check to see if table already exists. Get meta region where
    // table would sit should it exist. Open scanner on it. If a region
    // for the table we want to create already exists, then table already
    // created. Throw already-exists exception.
    MetaRegion m = regionManager.getFirstMetaRegionForRegion(newRegions[0]);
    byte [] metaRegionName = m.getRegionName();
    HRegionInterface srvr = this.connection.getHRegionConnection(m.getServer());
    byte[] firstRowInTable = Bytes.toBytes(tableName + ",,");
    Scan scan = new Scan(firstRowInTable);
    scan.addColumn(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER);
    long scannerid = srvr.openScanner(metaRegionName, scan);
    try {
      Result data = srvr.next(scannerid);
      if (data != null && data.size() > 0) {
        HRegionInfo info = Writables.getHRegionInfo(
          data.getValue(HConstants.CATALOG_FAMILY,
              HConstants.REGIONINFO_QUALIFIER));
        if (info.getTableDesc().getNameAsString().equals(tableName)) {
          // A region for this table already exists. Ergo table exists.
          throw new TableExistsException(tableName);
        }
      }
    } finally {
      srvr.close(scannerid);
    }
    for(HRegionInfo newRegion : newRegions) {
      regionManager.createRegion(newRegion, srvr, metaRegionName);
    }
    // kick off a meta scan right away to assign the newly created regions
    regionManager.metaScannerThread.triggerNow();
  }

  @Override
  public void deleteTable(final byte [] tableName) throws IOException {
    if (Bytes.equals(tableName, HConstants.ROOT_TABLE_NAME)) {
      throw new IOException("Can't delete root table");
    }
    new TableDelete(this, tableName).process();
    LOG.info("deleted table: " + Bytes.toString(tableName));
  }

  @Override
  public void alterTable(final byte [] tableName,
      List<HColumnDescriptor> columnAdditions,
      List<Pair<byte[], HColumnDescriptor>> columnModifications,
      List<byte[]> columnDeletions) throws IOException {
    ThrottledRegionReopener reopener = this.regionManager.
            createThrottledReopener(Bytes.toString(tableName));
    // Regions are added to the reopener in MultiColumnOperation
    new MultiColumnOperation(this, tableName, columnAdditions,
        columnModifications, columnDeletions).process();
    reopener.reOpenRegionsThrottle();
  }

  public Pair<Integer, Integer> getAlterStatus(byte[] tableName)
      throws IOException {
    Pair <Integer, Integer> p = new Pair<Integer, Integer>(0,0);
    if (regionManager.getThrottledReopener(Bytes.toString(tableName)) != null) {
      p = regionManager.getThrottledReopener(
                    Bytes.toString(tableName)).getReopenStatus();
    } else {
      // Table is not reopening any regions return (0,0)
    }
    return p;
  }

  @Override
  public void addColumn(byte [] tableName, HColumnDescriptor column)
  throws IOException {
    alterTable(tableName, Arrays.asList(column), null, null);
  }

  @Override
  public void modifyColumn(byte [] tableName, byte [] columnName,
    HColumnDescriptor descriptor)
  throws IOException {
    alterTable(tableName, null, Arrays.asList(
          new Pair<byte [], HColumnDescriptor>(columnName, descriptor)), null);
  }

  @Override
  public void deleteColumn(final byte [] tableName, final byte [] c)
  throws IOException {
    alterTable(tableName, null, null,
        Arrays.asList(KeyValue.parseColumn(c)[0]));
  }

  @Override
  public void enableTable(final byte [] tableName) throws IOException {
    if (Bytes.equals(tableName, HConstants.ROOT_TABLE_NAME)) {
      throw new IOException("Can't enable root table");
    }
    new ChangeTableState(this, tableName, true).process();
  }

  @Override
  public void disableTable(final byte [] tableName) throws IOException {
    if (Bytes.equals(tableName, HConstants.ROOT_TABLE_NAME)) {
      throw new IOException("Can't disable root table");
    }
    new ChangeTableState(this, tableName, false).process();
  }

  /**
   * Get a list of the regions for a given table. The pairs may have
   * null for their second element in the case that they are not
   * currently deployed.
   * TODO: Redo so this method does not duplicate code with subsequent methods.
   */
  public List<Pair<HRegionInfo,HServerAddress>> getTableRegions(
      final byte [] tableName)
  throws IOException {
    final ArrayList<Pair<HRegionInfo, HServerAddress>> result =
      Lists.newArrayList();

    if (!Bytes.equals(HConstants.META_TABLE_NAME, tableName)) {
      MetaScannerVisitor visitor =
        new MetaScannerVisitor() {
          @Override
          public boolean processRow(Result data) throws IOException {
            if (data == null || data.size() <= 0)
              return true;
            Pair<HRegionInfo, HServerAddress> pair =
              metaRowToRegionPair(data);
            if (pair == null) return false;
            if (!Bytes.equals(pair.getFirst().getTableDesc().getName(),
                  tableName)) {
              return false;
            }
            result.add(pair);
            return true;
          }
      };

      MetaScanner.metaScan(conf, visitor, tableName);
    }
    else {
      List<MetaRegion> metaRegions = regionManager.getListOfOnlineMetaRegions();
	for (MetaRegion mRegion: metaRegions) {
		if (Bytes.equals(mRegion.getRegionInfo().getTableDesc().getName(), tableName)) {
			result.add(new Pair<HRegionInfo, HServerAddress>
              (mRegion.getRegionInfo(), mRegion.getServer()));
		}
	}
    }
    return result;
  }

  private Pair<HRegionInfo, HServerAddress> metaRowToRegionPair(
      Result data) throws IOException {
    HRegionInfo info = Writables.getHRegionInfo(
        data.getValue(HConstants.CATALOG_FAMILY,
            HConstants.REGIONINFO_QUALIFIER));
    final byte[] value = data.getValue(HConstants.CATALOG_FAMILY,
        HConstants.SERVER_QUALIFIER);
    if (value != null && value.length > 0) {
      HServerAddress server = new HServerAddress(Bytes.toString(value));
      return new Pair<HRegionInfo,HServerAddress>(info, server);
    } else {
      //undeployed
      return new Pair<HRegionInfo, HServerAddress>(info, null);
    }
  }

  /**
   * Return the region and current deployment for the region containing
   * the given row. If the region cannot be found, returns null. If it
   * is found, but not currently deployed, the second element of the pair
   * may be null.
   */
  Pair<HRegionInfo,HServerAddress> getTableRegionForRow(
      final byte [] tableName, final byte [] rowKey)
  throws IOException {
    final AtomicReference<Pair<HRegionInfo, HServerAddress>> result =
      new AtomicReference<Pair<HRegionInfo, HServerAddress>>(null);

    MetaScannerVisitor visitor =
      new MetaScannerVisitor() {
        @Override
        public boolean processRow(Result data) throws IOException {
          if (data == null || data.size() <= 0)
            return true;
          Pair<HRegionInfo, HServerAddress> pair =
            metaRowToRegionPair(data);
          if (pair == null) return false;
          if (!Bytes.equals(pair.getFirst().getTableDesc().getName(),
                tableName)) {
            return false;
          }
          result.set(pair);
          return true;
        }
    };

    MetaScanner.metaScan(conf, visitor, tableName, rowKey, 1);
    return result.get();
  }

  Pair<HRegionInfo,HServerAddress> getTableRegionFromName(
      final byte [] regionName)
  throws IOException {
    byte [] tableName = HRegionInfo.parseRegionName(regionName)[0];

    Set<MetaRegion> regions = regionManager.getMetaRegionsForTable(tableName);
    for (MetaRegion m: regions) {
      byte [] metaRegionName = m.getRegionName();
      HRegionInterface srvr = connection.getHRegionConnection(m.getServer());
      Get get = new Get(regionName);
      get.addColumn(HConstants.CATALOG_FAMILY,
          HConstants.REGIONINFO_QUALIFIER);
      get.addColumn(HConstants.CATALOG_FAMILY, HConstants.SERVER_QUALIFIER);
      Result data = srvr.get(metaRegionName, get);
      if(data == null || data.size() <= 0) continue;
      return metaRowToRegionPair(data);
    }
    return null;
  }

  /**
   * Get row from meta table.
   * @param row
   * @param family
   * @return Result
   * @throws IOException
   */
  protected Result getFromMETA(final byte [] row, final byte [] family)
  throws IOException {
    MetaRegion meta = this.regionManager.getMetaRegionForRow(row);
    HRegionInterface srvr = getMETAServer(meta);
    Get get = new Get(row);
    get.addFamily(family);
    return srvr.get(meta.getRegionName(), get);
  }

  /*
   * @param meta
   * @return Server connection to <code>meta</code> .META. region.
   * @throws IOException
   */
  private HRegionInterface getMETAServer(final MetaRegion meta)
  throws IOException {
    return this.connection.getHRegionConnection(meta.getServer());
  }

  /**
   * Method for getting the tableDescriptor
   * @param tableName as a byte []
   * @return the tableDescriptor
   * @throws IOException if a remote or network exception occurs
   */
  public HTableDescriptor getTableDescriptor(final byte [] tableName)
  throws IOException {
    return this.connection.getHTableDescriptor(tableName);
  }

  @Override
  public void modifyTable(final byte[] tableName, HConstants.Modify op,
      Writable[] args)
  throws IOException {
    switch (op) {
    case TABLE_SET_HTD:
      if (args == null || args.length < 1 ||
          !(args[0] instanceof HTableDescriptor))
        throw new IOException("SET_HTD request requires an HTableDescriptor");
      HTableDescriptor htd = (HTableDescriptor) args[0];
      LOG.info("modifyTable(SET_HTD): " + htd);
      new ModifyTableMeta(this, tableName, htd).process();
      break;

    case TABLE_SPLIT:
    case TABLE_COMPACT:
    case TABLE_MAJOR_COMPACT:
    case TABLE_FLUSH:
      if (args != null && args.length > 0) {
        if (!(args[0] instanceof ImmutableBytesWritable))
          throw new IOException(
            "request argument must be ImmutableBytesWritable");
        Pair<HRegionInfo,HServerAddress> pair = null;
        if(tableName == null) {
          byte [] regionName = ((ImmutableBytesWritable)args[0]).get();
          pair = getTableRegionFromName(regionName);
        } else {
          byte [] rowKey = ((ImmutableBytesWritable)args[0]).get();
          pair = getTableRegionForRow(tableName, rowKey);
        }
        LOG.info("About to " + op.toString() + " on " + Bytes.toString(tableName) + " and pair is " + pair);
        if (pair != null && pair.getSecond() != null) {
          // If the column family name is specified, we need to perform a
          // column family specific action instead of an action on the whole
          // region. For this purpose the second value in args is the column
          // family name.
          if (args.length == 2) {
            byte[] regionTableName = HRegionInfo.parseRegionName(
                pair.getFirst().getRegionName())[0];
            byte [] columnFamily = ((ImmutableBytesWritable)args[1]).get();
            if (getTableDescriptor(regionTableName).hasFamily(columnFamily)) {
              this.regionManager.startCFAction(pair.getFirst().getRegionName(),
                  columnFamily, pair.getFirst(), pair.getSecond(), op);
            }
          } else {
            this.regionManager.startAction(pair.getFirst().getRegionName(),
                pair.getFirst(), pair.getSecond(), op);
          }
        }
      } else {
        for (Pair<HRegionInfo,HServerAddress> pair: getTableRegions(tableName)) {
          if (pair.getSecond() == null) continue; // undeployed
          this.regionManager.startAction(pair.getFirst().getRegionName(),
            pair.getFirst(), pair.getSecond(), op);
        }
      }
      break;

    // format : {tableName row | region} splitPoint
    case TABLE_EXPLICIT_SPLIT:
      if (args == null || args.length < (tableName == null? 2 : 1)) {
        throw new IOException("incorrect number of arguments given");
      }
      Pair<HRegionInfo,HServerAddress> pair = null;
      byte[] splitPoint = null;

      // split a single region
      if(tableName == null) {
        byte [] regionName = ((ImmutableBytesWritable)args[0]).get();
        pair = getTableRegionFromName(regionName);
        splitPoint = ((ImmutableBytesWritable)args[1]).get();
      } else {
        splitPoint = ((ImmutableBytesWritable)args[0]).get();
        pair = getTableRegionForRow(tableName, splitPoint);
      }
      if (pair == null) {
        throw new IOException("couldn't find RegionInfo from region name");
      } else if (splitPoint == null) {
        throw new IOException("must give explicit split point");
      } else if (!pair.getFirst().containsRow(splitPoint)) {
        throw new IOException("split point outside specified region's range");
      }
      HRegionInfo r = pair.getFirst();
      r.setSplitPoint(splitPoint);
      LOG.info("About to " + op.toString() + " on " +
               Bytes.toString(pair.getFirst().getTableDesc().getName()) +
               " at " + Bytes.toString(splitPoint) +
               " and pair is " + pair);
      if (pair.getSecond() != null) {
        this.regionManager.startAction(pair.getFirst().getRegionName(),
          pair.getFirst(), pair.getSecond(), Modify.TABLE_SPLIT);
      }
      break;

    case MOVE_REGION: {
      if (args == null || args.length != 2) {
        throw new IOException("Requires a region name and a hostname");
      }
      // Arguments are region name and an region server hostname.
      byte [] regionname = ((ImmutableBytesWritable)args[0]).get();

      // Need hri
      Result rr = getFromMETA(regionname, HConstants.CATALOG_FAMILY);
      HRegionInfo hri = getHRegionInfo(rr.getRow(), rr);
      String hostnameAndPort = Bytes.toString(((ImmutableBytesWritable)args[1]).get());
      HServerAddress serverAddress = new HServerAddress(hostnameAndPort);

      // Assign the specified host to be the preferred host for the specified region.
      this.regionManager.addRegionToPreferredAssignment(serverAddress, hri);

      // Close the region so that it will be re-opened by the preferred host.
      modifyTable(tableName, HConstants.Modify.CLOSE_REGION, new Writable[]{args[0]});
      break;
    }

    case CLOSE_REGION:
      if (args == null || args.length < 1 || args.length > 2) {
        throw new IOException("Requires at least a region name; " +
          "or cannot have more than region name and servername");
      }
      // Arguments are regionname and an optional server name.
      byte [] regionname = ((ImmutableBytesWritable)args[0]).get();
      LOG.debug("Attempting to close region: " + Bytes.toStringBinary(regionname));
      String hostnameAndPort = null;
      if (args.length == 2) {
        hostnameAndPort = Bytes.toString(((ImmutableBytesWritable)args[1]).get());
      }
      // Need hri
      Result rr = getFromMETA(regionname, HConstants.CATALOG_FAMILY);
      HRegionInfo hri = getHRegionInfo(rr.getRow(), rr);
      if (hostnameAndPort == null) {
        // Get server from the .META. if it wasn't passed as argument
        hostnameAndPort =
          Bytes.toString(rr.getValue(HConstants.CATALOG_FAMILY,
              HConstants.SERVER_QUALIFIER));
      }
      // Take region out of the intransistions in case it got stuck there doing
      // an open or whatever.
      this.regionManager.clearFromInTransition(regionname);
      // If hostnameAndPort is still null, then none, exit.
      if (hostnameAndPort == null) break;
      long startCode =
        Bytes.toLong(rr.getValue(HConstants.CATALOG_FAMILY,
            HConstants.STARTCODE_QUALIFIER));
      String name = HServerInfo.getServerName(hostnameAndPort, startCode);
      LOG.info("Marking " + hri.getRegionNameAsString() +
        " as closing on " + name + "; cleaning SERVER + STARTCODE; " +
          "master will tell regionserver to close region on next heartbeat");
      this.regionManager.setClosing(name, hri, hri.isOffline());
      MetaRegion meta = this.regionManager.getMetaRegionForRow(regionname);
      HRegionInterface srvr = getMETAServer(meta);
      HRegion.cleanRegionInMETA(srvr, meta.getRegionName(), hri);
      break;

    default:
      throw new IOException("unsupported modifyTable op " + op);
    }
  }

  /**
   * @return cluster status
   */
  @Override
  public ClusterStatus getClusterStatus() {
    ClusterStatus status = new ClusterStatus();
    status.setHBaseVersion(VersionInfo.getVersion());
    status.setServerInfo(serverManager.getServersToServerInfo().values());
    status.setDeadServers(serverManager.getDeadServers());
    status.setRegionsInTransition(this.regionManager.getRegionsInTransition());
    return status;
  }

  // TODO ryan rework this function
  /*
   * Get HRegionInfo from passed META map of row values.
   * Returns null if none found (and logs fact that expected COL_REGIONINFO
   * was missing).  Utility method used by scanners of META tables.
   * @param row name of the row
   * @param map Map to do lookup in.
   * @return Null or found HRegionInfo.
   * @throws IOException
   */
  HRegionInfo getHRegionInfo(final byte [] row, final Result res)
  throws IOException {
    byte[] regioninfo = res.getValue(HConstants.CATALOG_FAMILY,
        HConstants.REGIONINFO_QUALIFIER);
    if (regioninfo == null) {
      StringBuilder sb =  new StringBuilder();
      NavigableMap<byte[], byte[]> infoMap =
        res.getFamilyMap(HConstants.CATALOG_FAMILY);
      for (byte [] e: infoMap.keySet()) {
        if (sb.length() > 0) {
          sb.append(", ");
        }
        sb.append(Bytes.toString(HConstants.CATALOG_FAMILY) + ":"
            + Bytes.toString(e));
      }
      LOG.warn(Bytes.toString(HConstants.CATALOG_FAMILY) + ":" +
          Bytes.toString(HConstants.REGIONINFO_QUALIFIER)
          + " is empty for row: " + Bytes.toString(row) + "; has keys: "
          + sb.toString());
      return null;
    }
    return Writables.getHRegionInfo(regioninfo);
  }

  /*
   * When we find rows in a meta region that has an empty HRegionInfo, we
   * clean them up here.
   *
   * @param s connection to server serving meta region
   * @param metaRegionName name of the meta region we scanned
   * @param emptyRows the row keys that had empty HRegionInfos
   */
  protected void deleteEmptyMetaRows(HRegionInterface s,
      byte [] metaRegionName,
      List<byte []> emptyRows) {
    for (byte [] regionName: emptyRows) {
      try {
        HRegion.removeRegionFromMETA(s, metaRegionName, regionName);
        LOG.warn("Removed region: " + Bytes.toString(regionName) +
          " from meta region: " +
          Bytes.toString(metaRegionName) + " because HRegionInfo was empty");
      } catch (IOException e) {
        LOG.error("deleting region: " + Bytes.toString(regionName) +
            " from meta region: " + Bytes.toString(metaRegionName), e);
      }
    }
  }

  /**
   * @see org.apache.zookeeper.Watcher#process(org.apache.zookeeper.WatchedEvent)
   */
  @Override
  public void process(WatchedEvent event) {
    // no-op now
  }

  private static void printUsageAndExit() {
    System.err.println("Usage: Master [opts] start|stop");
    System.err.println(" start  Start Master. If local mode, start Master and RegionServer in same JVM");
    System.err.println(" stop   Start cluster shutdown; Master signals RegionServer shutdown");
    System.err.println(" where [opts] are:");
    System.err.println("   --minServers=<servers>    Minimum RegionServers needed to host user tables.");
    System.err.println("   -D opt=<value>            Override HBase configuration settings.");
    System.exit(0);
  }

  /**
   * Utility for constructing an instance of the passed HMaster class.
   * @param masterClass
   * @param conf
   * @return HMaster instance.
   */
  public static HMaster constructMaster(Class<? extends HMaster> masterClass,
      final Configuration conf)  {
    try {
      Constructor<? extends HMaster> c =
        masterClass.getConstructor(Configuration.class);
      return c.newInstance(conf);
    } catch (Exception e) {
      throw new RuntimeException("Failed construction of " +
        "Master: " + masterClass.toString() +
        ((e.getCause() != null)? e.getCause().getMessage(): ""), e);
    }
  }

  /*
   * Version of master that will shutdown the passed zk cluster on its way out.
   */
  static class LocalHMaster extends HMaster {
    private MiniZooKeeperCluster zkcluster = null;

    public LocalHMaster(Configuration conf) throws IOException {
      super(conf);
    }

    @Override
    public void run() {
      super.run();
      if (this.zkcluster != null) {
        try {
          this.zkcluster.shutdown();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    void setZKCluster(final MiniZooKeeperCluster zkcluster) {
      this.zkcluster = zkcluster;
    }
  }

  protected static void doMain(String [] args,
      Class<? extends HMaster> masterClass) {
    Configuration conf = HBaseConfiguration.create();

    Options opt = new Options();
    opt.addOption("minServers", true, "Minimum RegionServers needed to host user tables");
    opt.addOption("D", true, "Override HBase Configuration Settings");
    opt.addOption("backup", false, "Do not try to become HMaster until the primary fails");
    try {
      CommandLine cmd = new GnuParser().parse(opt, args);

      if (cmd.hasOption("minServers")) {
        String val = cmd.getOptionValue("minServers");
        conf.setInt("hbase.regions.server.count.min",
            Integer.valueOf(val));
        LOG.debug("minServers set to " + val);
      }

      if (cmd.hasOption("D")) {
        for (String confOpt : cmd.getOptionValues("D")) {
          String[] kv = confOpt.split("=", 2);
          if (kv.length == 2) {
            conf.set(kv[0], kv[1]);
            LOG.debug("-D configuration override: " + kv[0] + "=" + kv[1]);
          } else {
            throw new ParseException("-D option format invalid: " + confOpt);
          }
        }
      }

      // check if we are the backup master - override the conf if so
      if (cmd.hasOption("backup")) {
        conf.setBoolean(HConstants.MASTER_TYPE_BACKUP, true);
      }

      if (cmd.getArgList().contains("start")) {
        try {
          // Print out vm stats before starting up.
          RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
          if (runtime != null) {
            LOG.info("vmName=" + runtime.getVmName() + ", vmVendor=" +
              runtime.getVmVendor() + ", vmVersion=" + runtime.getVmVersion());
            LOG.info("vmInputArguments=" + runtime.getInputArguments());
          }
          // If 'local', defer to LocalHBaseCluster instance.  Starts master
          // and regionserver both in the one JVM.
          if (LocalHBaseCluster.isLocal(conf)) {
            final MiniZooKeeperCluster zooKeeperCluster =
              new MiniZooKeeperCluster();
            File zkDataPath = new File(conf.get("hbase.zookeeper.property.dataDir"));
            int zkClientPort = conf.getInt("hbase.zookeeper.property.clientPort", 0);
            if (zkClientPort == 0) {
              throw new IOException("No config value for hbase.zookeeper.property.clientPort");
            }
            zooKeeperCluster.setTickTime(conf.getInt("hbase.zookeeper.property.tickTime", 3000));
            zooKeeperCluster.setClientPort(zkClientPort);
            int clientPort = zooKeeperCluster.startup(zkDataPath);
            if (clientPort != zkClientPort) {
              String errorMsg = "Couldnt start ZK at requested address of " +
                  zkClientPort + ", instead got: " + clientPort + ". Aborting. Why? " +
                  "Because clients (eg shell) wont be able to find this ZK quorum";
              System.err.println(errorMsg);
              throw new IOException(errorMsg);
            }
            conf.set("hbase.zookeeper.property.clientPort",
              Integer.toString(clientPort));
            // Need to have the zk cluster shutdown when master is shutdown.
            // Run a subclass that does the zk cluster shutdown on its way out.
            LocalHBaseCluster cluster = new LocalHBaseCluster(conf, 1, 1,
              LocalHMaster.class, HRegionServer.class);
            ((LocalHMaster)cluster.getMaster()).setZKCluster(zooKeeperCluster);
            cluster.startup();
          } else {
            HMaster master = constructMaster(masterClass, conf);
            if (master.shutdownRequested.get()) {
              LOG.info("Won't bring the Master up as a shutdown is requested");
              return;
            }
            master.start();
          }
        } catch (Throwable t) {
          LOG.error("Failed to start master", t);
          System.exit(-1);
        }
      } else if (cmd.getArgList().contains("stop")) {
        HBaseAdmin adm = null;
        try {
          adm = new HBaseAdmin(conf);
        } catch (MasterNotRunningException e) {
          LOG.error("Master not running");
          System.exit(0);
        }
        try {
          adm.shutdown();
        } catch (Throwable t) {
          LOG.error("Failed to stop master", t);
          System.exit(-1);
        }
      } else {
        throw new ParseException("Unknown argument(s): " +
            org.apache.commons.lang.StringUtils.join(cmd.getArgs(), " "));
      }
    } catch (ParseException e) {
      LOG.error("Could not parse: ", e);
      printUsageAndExit();
    }
  }

  public Map<String, Integer> getTableFragmentation() throws IOException {
    long now = System.currentTimeMillis();
    // only check every two minutes by default
    int check = this.conf.getInt("hbase.master.fragmentation.check.frequency", 2 * 60 * 1000);
    if (lastFragmentationQuery == -1 || now - lastFragmentationQuery > check) {
      fragmentation = FSUtils.getTableFragmentation(this);
      lastFragmentationQuery = now;
    }
    return fragmentation;
  }

  @Override
  public ProtocolSignature getProtocolSignature(String protocol,
      long clientVersion, int clientMethodsHash) throws IOException {
    return ProtocolSignature.getProtocolSignature(
        this, protocol, clientVersion, clientMethodsHash);
  }

  /**
   * Report whether this master is currently the active master or not.
   * If not active master, we are parked on ZK waiting to become active.
   *
   * This method is used for testing.
   *
   * @return true if active master, false if not.
   */
  public boolean isActiveMaster() {
    return isActiveMaster;
  }

  public String getServerName() {
    return address.toString();
  }

  /**
   * Main program
   * @param args
   */
  public static void main(String [] args) {
    doMain(args, HMaster.class);
  }

  @Override
  public void clearFromTransition(HRegionInfo region) {
    this.regionManager.clearFromInTransition(region.getRegionName());
    LOG.info("Cleared region " + region + " from transition map");
  }

  public void stopMaster() {
    closed.set(true);
  }

}
