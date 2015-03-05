package deegan;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.nio.ByteBuffer;

import org.apache.cassandra.CleanupHelper;
import org.apache.cassandra.client.ClientLibrary;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.cops2.Cops2Test;
import org.apache.cassandra.locator.NetworkTopologyStrategy;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.thrift.*;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.LamportClock;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.hsqldb.SchemaManager;
import org.apache.cassandra.locator.SimpleStrategy;

public class TestClient {

    private final int DEFAULT_THRIFT_PORT = 9160;
    private final String MAIN_KEYSPACE = "KeySpace1";
    private final String MAIN_COLUMN_FAMILY = "ColumnFam1";

    private Map<String, Integer> localServerIPAndPorts = new HashMap<String, Integer>();
    private List<Map<String, Integer>> dcToServerIPAndPorts = null;
    private ConsistencyLevel consistencyLevel;
    
    public static void main(String[] args) {
        TestClient client = new TestClient();
    }

    /**
     * Constructor
     */
    public TestClient() {
        this.setup();
        try{
        	print("yoyo");
        	this.trySomePutsAndGets();
        }
        catch(Exception e){
        	e.printStackTrace();
        }
    }
    
    /**
     * Prints to System out
     * @param str
     */
    private void print(String str) {
    	System.out.println(str);
    }
    
    //// Helpers 
    private static Column newColumn(String name) {
        return new Column(ByteBufferUtil.bytes(name));
    }

    private static Column newColumn(String name, String value) {
        return new Column(ByteBufferUtil.bytes(name)).setValue(ByteBufferUtil.bytes(value)).setTimestamp(0L);
    }

    private static Column newColumn(String name, String value, long timestamp) {
        return new Column(ByteBufferUtil.bytes(name)).setValue(ByteBufferUtil.bytes(value)).setTimestamp(timestamp);
    }

    private static CounterColumn newCounterColumn(String name, long value) {
        return new CounterColumn(ByteBufferUtil.bytes(name), value);
    }

    
    private void waitForKeyspacePropagation(Map<String, Integer> allServerIPAndPorts, String keyspace) throws TException
    {
        System.out.println("Waiting for key propagation...");
        for (Entry<String, Integer> ipAndPort : allServerIPAndPorts.entrySet()) {
            String ip = ipAndPort.getKey();
            Integer port = ipAndPort.getValue();

            TTransport tFramedTransport = new TFramedTransport(new TSocket(ip, port));
            TProtocol binaryProtoOnFramed = new TBinaryProtocol(tFramedTransport);
            Cassandra.Client client = new Cassandra.Client(binaryProtoOnFramed);
            tFramedTransport.open();

            // FIXME: This is a hideous way to ensure the earlier system_add_keyspace has propagated everywhere
            while(true) {
                try {
                    client.set_keyspace(keyspace, LamportClock.sendTimestamp());
                    break;
                } catch (InvalidRequestException e) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e1) {
                        //ignore
                    }
                }
            }
        }
        System.out.println("Keys propagated.");

    }
   
    /**
     * Creates a keyspace if it dosent already exist
     * @param client the cassandra thrift client
     * @param name the name of the keyspace to create
     * @throws TException
     * @throws InvalidRequestException
     */
    private void setupKeyspace(Cassandra.Iface client, String keyspace) throws TException, InvalidRequestException
    {
    	List<KsDef> yo = client.describe_keyspaces();

    	print("Current Keyspaces: -------");
    	for(KsDef def: yo){
    		print(def.name);
    		if(def.name.equals(keyspace)){
    			print(keyspace + " already exists with strategy: " + def.getStrategy_class() + "... continue");
    			return;
    		}
    	}
    	print("---------------------------");
    	
        List<CfDef> cfDefList = new ArrayList<CfDef>();
        CfDef columnFamily = new CfDef(keyspace, MAIN_COLUMN_FAMILY);
        cfDefList.add(columnFamily);

        try 
        {	
        	KsDef keySpaceDefenition = new KsDef();
        	keySpaceDefenition.name = keyspace;
        	keySpaceDefenition.strategy_class = SimpleStrategy.class.getName();
        	if (keySpaceDefenition.strategy_options == null) 
        		keySpaceDefenition.strategy_options = new LinkedHashMap<String, String>();
        	keySpaceDefenition.strategy_options.put("replication_factor", "1");
        	keySpaceDefenition.cf_defs = cfDefList;

            client.system_add_keyspace(keySpaceDefenition);
            
        	print("got this far");
            int magnitude = client.describe_ring(keyspace).size();
            print("magnitude: " + magnitude);
            try
            {
                Thread.sleep(1000 * magnitude);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
        catch (InvalidRequestException probablyExists) 
        {
            System.out.println("Problem creating keyspace: " + probablyExists.getMessage()); 
        	
        }
        catch (Exception e){
        	print("excpetion here now...");
        	e.printStackTrace();
        }
    }

    /**
     * Modified from cops2 unit tests
     */
    private void setup() {
    	print("setup started");
    	Integer numDatacenters = 1;
    	Integer nodesPerDatacenter = 1;
    	
    	HashMap<String, Integer> localServerIPAndPorts = new HashMap<String, Integer>();
        for (int i = 1; i <= nodesPerDatacenter; ++i) {
            localServerIPAndPorts.put("127.0.0." + i, DEFAULT_THRIFT_PORT);
        }
        
    	try{
        	//Create a keyspace with a replication factor of 1 for each datacenter
        	TTransport tr = new TFramedTransport(new TSocket("localhost", DEFAULT_THRIFT_PORT));
        	TProtocol proto = new TBinaryProtocol(tr);
        	Cassandra.Client client = new Cassandra.Client(proto);
        	tr.open();

        	this.setupKeyspace(client, MAIN_KEYSPACE);
            this.waitForKeyspacePropagation(localServerIPAndPorts, MAIN_KEYSPACE);
    	}catch(Exception c){
            System.out.println("An exception occured: " + c);
    		return;
    	}
    	
    	
        
        this.localServerIPAndPorts = localServerIPAndPorts;
        this.consistencyLevel = ConsistencyLevel.ONE;
        
        print("setup done");
    }
    
    private void trySomePutsAndGets() throws Exception {
        //setup the client library
    	ClientLibrary lib = new ClientLibrary(this.localServerIPAndPorts, MAIN_KEYSPACE, this.consistencyLevel);
    	//intialize some sample keys
    	ByteBuffer key1 = ByteBufferUtil.bytes("tdeegan2");
    	ByteBuffer key2 = ByteBufferUtil.bytes("ltseng3");
    	
    	String firstNameColumn = "first_name";
    	ByteBuffer firstNameColumnBuffer = ByteBufferUtil.bytes(firstNameColumn);
    	
    	ColumnParent columnParent = new ColumnParent(MAIN_COLUMN_FAMILY);
    	long timestamp = System.currentTimeMillis();


    	try{
    		lib.insert(key1, columnParent, newColumn(firstNameColumn, "thomas", timestamp));
        	lib.insert(key2, columnParent, newColumn(firstNameColumn, "lewis", timestamp));
    	}
    	catch(InvalidRequestException e){
    		print("invalid request: ");
    		e.printStackTrace();
    	}
    	print("got this far");

    	
    	ColumnPath cp = new ColumnPath(MAIN_COLUMN_FAMILY);
        cp.column = firstNameColumnBuffer;
        ColumnOrSuperColumn got1 = lib.get(key1, cp);
        ColumnOrSuperColumn got2 = lib.get(key2, cp);
        
        print("-- tdeegan2: " + new String(got1.getColumn().getValue()));
        print("-- ltseng3: " + new String(got2.getColumn().getValue()));
        
    	print("done with insert");
    }
}