// **********************************************************************
//
// Copyright (c) 2003-2007 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************

package Freeze;

class SharedDbEnv implements com.sleepycat.db.ErrorHandler, Runnable
{
    public static SharedDbEnv
    get(Ice.Communicator communicator, String envName, com.sleepycat.db.Environment dbEnv)
    {
        MapKey key = new MapKey(envName, communicator);

        SharedDbEnv result;

        synchronized(_map) 
        {
            result = (SharedDbEnv)_map.get(key);
            if(result == null)
            {
                try
                {
                    result = new SharedDbEnv(key, dbEnv);
                }
                catch(com.sleepycat.db.DatabaseException dx)
                {
                    DatabaseException ex = new DatabaseException();
                    ex.initCause(dx);
                    ex.message = errorPrefix(envName) + "creation: " + dx.getMessage();
                    throw ex;
                }

                Object previousValue = _map.put(key, result);
                assert(previousValue == null);
            }
            else
            {
                result._refCount++;
            }
        }

        //
        // Make sure the result if fully initialized
        //
        result.init();
        return result;
    }
    
    public String 
    getEnvName()
    {
        return _key.envName;
    }

    public Ice.Communicator
    getCommunicator()
    {
        return _key.communicator;
    }

    public com.sleepycat.db.Environment
    getEnv()
    {
        return _dbEnv;
    }

    public SharedDb
    getCatalog()
    {
        return _catalog;
    }

    public void
    close()
    {
        synchronized(_map) 
        {
            if(--_refCount == 0)
            {       
                //
                // Remove from map
                //
                Object value = _map.remove(_key);
                assert(value == this);

                //
                // Join thread
                //
                synchronized(this)
                {
                    _done = true;
                    notify();
                }

                for(;;)
                {
                    if(_thread != null)
                    {
                        try
                        {
                            _thread.join();
                            _thread = null;
                            break;
                        }
                        catch(InterruptedException ex)
                        {
                        }
                    }
                }

                //
                // Release catalog
                //
                if(_catalog != null)
                {
                    _catalog.close();
                    _catalog = null;
                }

                if(_trace >= 1)
                {
                    _key.communicator.getLogger().trace("Freeze.DbEnv", "closing database environment \"" +
                                                        _key.envName + "\"");
                }

                //
                // Keep lock to prevent somebody else from re-opening this DbEnv
                // before it's closed.
                //
                try
                {
                    _dbEnv.close();
                }
                catch(com.sleepycat.db.DatabaseException dx)
                {
                    DatabaseException ex = new DatabaseException();
                    ex.initCause(dx);
                    ex.message = errorPrefix(_key.envName) + "close: " + dx.getMessage();
                    throw ex;
                }
            }
        }
    }

    public void
    run()
    {
        for(;;)
        {
            synchronized(this)
            {
                while(!_done)
                {
                    try
                    {
                        wait(_checkpointPeriod);
                    }
                    catch(InterruptedException ex)
                    {
                        continue;
                    }
                    break;
                }
                if(_done)
                {
                    return;
                }
            }
            
            if(_trace >= 2)
            {
                _key.communicator.getLogger().trace("Freeze.DbEnv", "checkpointing environment \"" + _key.envName +
                                                    "\"");
            }

            try
            {
                com.sleepycat.db.CheckpointConfig config = new com.sleepycat.db.CheckpointConfig();
                config.setKBytes(_kbyte);
                _dbEnv.checkpoint(config);
            }
            catch(com.sleepycat.db.DatabaseException dx)
            {
                _key.communicator.getLogger().warning("checkpoint on DbEnv \"" + _key.envName +
                                                      "\" raised DbException: " + dx.getMessage());
            }
        }
    }
    
    public void 
    error(com.sleepycat.db.Environment env, String errorPrefix, String message)
    {
        _key.communicator.getLogger().error("Freeze database error in DbEnv \"" + _key.envName + "\": " + message);
    }


    //
    // EvictorContext factory/manager
    //
    

    //
    // Get/create an evictor context associated with the calling thread
    //
    synchronized TransactionalEvictorContextI
    getOrCreateCurrent(Ice.BooleanHolder created)
    {
        if(created != null)
        {
            created.value = false;
        }

        Object k = Thread.currentThread();

        TransactionalEvictorContextI ctx = (TransactionalEvictorContextI)_ctxMap.get(k);
        if(ctx == null)
        {
            ctx = new TransactionalEvictorContextI(this);
         
            if(created != null)
            {
                created.value = true;
            }
            _ctxMap.put(k, ctx);
        }
        return ctx;
    }

    synchronized TransactionalEvictorContextI
    getCurrent()
    {
        Object k = Thread.currentThread();
        return (TransactionalEvictorContextI)_ctxMap.get(k);
    }

    //
    // Clear evictor context associated with the calling thread
    //
    synchronized void
    clearCurrent(TransactionalEvictorContextI oldCtx)
    {
        Object removedCtx = _ctxMap.remove(Thread.currentThread());
        assert removedCtx == oldCtx;
    }

    
    private
    SharedDbEnv(MapKey key, com.sleepycat.db.Environment dbEnv)
        throws com.sleepycat.db.DatabaseException
    {   
        _key = key;
        _dbEnv = dbEnv;
        _ownDbEnv = (dbEnv == null);

        Ice.Properties properties = key.communicator.getProperties();
        _trace = properties.getPropertyAsInt("Freeze.Trace.DbEnv");

        if(_ownDbEnv)
        {
            com.sleepycat.db.EnvironmentConfig config = new com.sleepycat.db.EnvironmentConfig();

            config.setErrorHandler(this);
            config.setInitializeLocking(true);
            config.setInitializeLogging(true);
            config.setInitializeCache(true);
            config.setAllowCreate(true);
            config.setTransactional(true);

            //
            // Deadlock detection
            //
            config.setLockDetectMode(com.sleepycat.db.LockDetectMode.YOUNGEST);

            String propertyPrefix = "Freeze.DbEnv." + _key.envName;
            if(properties.getPropertyAsInt(propertyPrefix + ".DbRecoverFatal") != 0)
            {
                config.setRunFatalRecovery(true);
            }
            else
            {
                config.setRunRecovery(true);
            }

            if(properties.getPropertyAsIntWithDefault(propertyPrefix + ".DbPrivate", 1) != 0)
            {
                config.setPrivate(true);
            }

            if(properties.getPropertyAsIntWithDefault(propertyPrefix + ".OldLogsAutoDelete", 1) != 0)
            {
                config.setLogAutoRemove(true);
            }

            if(_trace >= 1)
            {
                _key.communicator.getLogger().trace("Freeze.DbEnv", "opening database environment \"" +
                                                    _key.envName + "\"");
            }

            //
            // TODO: FREEZE_DB_MODE
            //
            
            try
            {
                String dbHome = properties.getPropertyWithDefault(propertyPrefix + ".DbHome", _key.envName);
                java.io.File home = new java.io.File(dbHome);
                _dbEnv = new com.sleepycat.db.Environment(home, config);
            }
            catch(java.io.FileNotFoundException dx)
            {
                NotFoundException ex = new NotFoundException();
                ex.initCause(dx);
                ex.message = errorPrefix(_key.envName) + "open: " + dx.getMessage();
                throw ex;
            }
            
            //
            // Default checkpoint period is every 120 seconds
            //
            _checkpointPeriod =
                properties.getPropertyAsIntWithDefault(propertyPrefix + ".CheckpointPeriod", 120) * 1000;
            
            _kbyte = properties.getPropertyAsIntWithDefault(propertyPrefix + ".PeriodicCheckpointMinSize", 0);

            String threadName;
            String programName = properties.getProperty("Ice.ProgramName");
            if(programName.length() > 0)
            {
                threadName = programName + "-";
            }
            else
            {
                threadName = "";
            }
            threadName += "FreezeCheckpointThread(" + _key.envName + ")";

            if(_checkpointPeriod > 0)
            {
                _thread = new Thread(this, threadName);
                _thread.start();
            }    
        }

        _refCount = 1;
    }

    private synchronized void
    init()
    {
        if(_catalog == null)
        {
            _catalog = SharedDb.openCatalog(this);
        }
    }

    private static String
    errorPrefix(String envName)
    {
        return "DbEnv(\"" + envName + "\"): ";
    }

    private static class MapKey
    {
        final String envName;
        final Ice.Communicator communicator;
        
        MapKey(String envName, Ice.Communicator communicator)
        {
            this.envName = envName;
            this.communicator = communicator;
        }

        public boolean
        equals(Object o)
        {   
            try
            {
                MapKey k = (MapKey)o;
                return (communicator == k.communicator) && envName.equals(k.envName);
            }
            catch(ClassCastException ex)
            {
                communicator.getLogger().trace("Freeze.DbEnv", "equals cast failed");
                return false;
            }
        }
        
        public int hashCode()
        {
            return envName.hashCode() ^ communicator.hashCode();
        }
    }

    private MapKey _key;
    private com.sleepycat.db.Environment _dbEnv;
    private boolean _ownDbEnv;
    private SharedDb _catalog;
    private int _refCount = 0;
    private boolean _done = false;
    private int _trace = 0;
    private long _checkpointPeriod = 0;
    private int _kbyte = 0;
    private Thread _thread;

    private java.util.Map _ctxMap = new java.util.HashMap();

    //
    // Hash map of (MapKey, SharedDbEnv)
    //
    private static java.util.Map _map = new java.util.HashMap();
}
