package com.aerospike.redis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Info;
import com.aerospike.client.Key;
import com.aerospike.client.Language;
import com.aerospike.client.Operation;
import com.aerospike.client.Record;
import com.aerospike.client.ResultCode;
import com.aerospike.client.ScanCallback;
import com.aerospike.client.Value;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.policy.BatchPolicy;
import com.aerospike.client.policy.InfoPolicy;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.QueryPolicy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;

public class RedisClient{

	private AerospikeClient asClient;
	private WritePolicy writePolicy;
	private Policy policy;
	private BatchPolicy batchPolicy;
	private ScanPolicy scanPolicy;
	private QueryPolicy queryPolicy;
	private String namespace;
	private String redisBin = "redis-bin";
	private String redisSet = null;
	private String keyBin = "redis-key-bin";
	private InfoPolicy infoPolicy;
	
	private static final long AS_TIME_OFFSET = 1262304000000L;// in milliseconds

	public enum LIST_POSITION {
		BEFORE, AFTER;
	}

	public RedisClient() {
		super();
		this.writePolicy = new WritePolicy();
		this.writePolicy.recordExistsAction = RecordExistsAction.REPLACE;
		this.policy = new Policy();
		this.scanPolicy = new ScanPolicy();
		this.queryPolicy = new QueryPolicy();
		this.infoPolicy = new InfoPolicy();
		this.batchPolicy = new BatchPolicy();

	}


	public RedisClient(final String host, final int port, String namespace, String set) {
		this();
		this.asClient = new AerospikeClient(host, port);
		this.namespace = namespace;
		this.redisSet = set;
		checkUdfRegistration();
	}

	public RedisClient(AerospikeClient asClient, String namespace, String set) {
		this();
		this.asClient = asClient;
		this.namespace = namespace;
		this.redisSet = set;
		checkUdfRegistration();
	}

	public RedisClient(final String host, final int port, String namespace, String set, final int timeout) {
		this(host, port, namespace, set);
		setTimeout(timeout);
	}

	public void setTimeout(int timeout){
		this.policy.timeout = timeout;
		this.writePolicy.timeout = timeout;
		this.scanPolicy.timeout = timeout;
		this.queryPolicy.timeout = timeout;
		this.infoPolicy.timeout = timeout;
		this.batchPolicy.timeout = timeout;
	}

	private void checkUdfRegistration(){
		String modules = info("udf-list");
		if (modules.contains("redis.lua"))
			return;
		this.asClient.register(batchPolicy, "udf/redis.lua", "redis.lua", Language.LUA);
	}
	
	private String[] infoAll(InfoPolicy infoPolicy, AerospikeClient client,
			String infoString) {
		String[] messages = new String[client.getNodes().length];
		int index = 0;
		for (Node node : client.getNodes()){
			messages[index] = Info.request(infoPolicy, node, infoString);
		}
		return messages;
	}

	private String info(String infoString) {
		if (this.asClient != null && this.asClient.isConnected()){
			String answer = Info.request(this.infoPolicy, this.asClient.getNodes()[0], infoString);
			return answer;
		} else {
			return "Client not connected";
		}
	}


	public String set(Object key, Object value){
		return set(null, key, value);
	}
	
	public String set(WritePolicy wp, Object key, Object value){
			Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
			Bin keyBin = new Bin(this.keyBin , key);
			Bin valueBin = new Bin(this.redisBin, Value.get(value));
			this.asClient.put((wp == null) ? this.writePolicy : wp, asKey, keyBin, valueBin);
			return "OK";
		
	}
	
	public String mset(final String... keysvalues) {
		if (keysvalues.length % 2 != 0)
			return "Keys and Values mismatch";
		String key = null;
		boolean isKey = true;
		for (String keyvalue : keysvalues){
			if (isKey) {
				key = keyvalue;
				isKey = false;
			} else {
				set(null, key, Value.get(keyvalue));
				isKey = true;
			}
		}
		return "OK";
	}
	
	public long msetnx(final String... keysvalues) {
		if (keysvalues.length % 2 != 0)
			return 0L;
		long retVal = 0L;
		String key = null;
		WritePolicy wp = new WritePolicy();
		wp.timeout = this.writePolicy.timeout;
		wp.recordExistsAction = RecordExistsAction.CREATE_ONLY;
		boolean isKey = true;
		try {
			for (String keyvalue : keysvalues){
				if (isKey) {
					key = keyvalue;
					isKey = false;
				} else {
					set(wp, key, Value.get(keyvalue));
					retVal++;
					isKey = true;
				}
			}
		} catch (AerospikeException e){
			if (e.getResultCode() != ResultCode.KEY_EXISTS_ERROR)
				throw e;
		}
		return retVal;
    }



	public String setex(Object key, int expiration, Object value) {
		WritePolicy wp = new WritePolicy();
		wp.expiration = expiration;
		set(wp, key, Value.get(value));
		return "OK";
	}


	
	public String psetex(Object key, int expiration, Object value) {
		return setex(key, expiration/1000, value);
	}

	public long setnx(Object key, Object value) {
		try {
			WritePolicy wp = new WritePolicy();
			wp.recordExistsAction = RecordExistsAction.CREATE_ONLY;
			set(wp, key, value);
			return 1;
		} catch (AerospikeException e){
			if (e.getResultCode() == ResultCode.KEY_EXISTS_ERROR)
				return 0;
			else
				throw e;
		}
	}

	public long setxx(Object key, Object value) {
		try {
			WritePolicy wp = new WritePolicy();
			wp.recordExistsAction = RecordExistsAction.REPLACE_ONLY;
			set(wp, key, value);
			return 1;
		} catch (AerospikeException e){
			if (e.getResultCode() == ResultCode.KEY_NOT_FOUND_ERROR)
				return 0;
			else
				throw e;
		}
	}

	public boolean exists(Object key) {
		Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
		return this.asClient.exists(this.writePolicy, asKey);
	}

	public long del(Object key) {
			Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
			this.asClient.delete(writePolicy, asKey);
			return 1;
	}



	public long del(Object ...keys) {
		long count = 0;
		for (Object key : keys){
			Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
			this.asClient.delete(writePolicy, asKey);
			count++;
		}
		return count;
	}



	public Set<String> keys(final String pattern) {
		final Set<String> result = new HashSet<String>();
		this.asClient.scanAll(this.scanPolicy, this.namespace, this.redisSet, new ScanCallback() {
			
			@Override
			public void scanCallback(Key key, Record record) throws AerospikeException {
				String keyString = (String) record.bins.get(keyBin);
				if (keyString.matches(pattern)){
					result.add(keyString);
				}
			}
		}, this.keyBin);
		
		return result;
	}


	public Set<byte[]> keys(byte[] binaryPattern) {
		final String pattern = binaryPattern.toString();
		final Set<byte[]> result = new HashSet<byte[]>();
		this.asClient.scanAll(this.scanPolicy, this.namespace, this.redisSet, new ScanCallback() {
			
			@Override
			public void scanCallback(Key key, Record record) throws AerospikeException {
				String keyString = (String) record.bins.get(keyBin);
				if (keyString.matches(pattern)){
					result.add(keyString.getBytes());
				}
			}
		}, this.keyBin);
		
		return result;
	}


	public String get(Object key) {
		Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
		Record record = this.asClient.get(this.policy, asKey, this.redisBin);
		if (record == null)
			return null;
		String value = (String) record.getValue(this.redisBin);
		return value;
	}


	public List<String> mget(Object ...keys) {
		Key[] asKeys = new Key[keys.length];
		for (int i = 0; i < keys.length; i++){
			asKeys[i] = new Key(this.namespace, this.redisSet, Value.get(keys[i]));
		}
		Record[] records = this.asClient.get(this.batchPolicy, asKeys, this.redisBin);
		List<String> result = new ArrayList<String>();
		for (Record record : records){
			result.add((record == null) ? null : (String) record.getValue(this.redisBin));
		}
		return result;
	}


	public String rename(Object oldKey, Object newKey) {
		Key oldAsKey = new Key(this.namespace, this.redisSet, Value.get(oldKey));
		Record record = this.asClient.get(policy, oldAsKey);
		this.set(newKey, (String) record.getValue(this.redisBin)); 
		this.asClient.delete(this.writePolicy, oldAsKey);
		return "OK";
	}


	public long expire(Object key, long expiration) {
		try {
			Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
			WritePolicy wp = new WritePolicy();
			wp.expiration = (int) expiration;
			wp.recordExistsAction = RecordExistsAction.UPDATE_ONLY;
			wp.timeout = this.writePolicy.timeout;
			this.asClient.touch(wp, asKey);
			return 1; 
		} catch (AerospikeException e) {
			if (e.getResultCode() == ResultCode.KEY_NOT_FOUND_ERROR){
				return 0;
			} else {
				throw e;
			}
		}
	}


	public long pexpire(Object key, long expiration) {
		return expire(key, expiration / 1000);
	}

	
	public long expireAt(Object key, long unixTime) {
		// TODO Auto-generated method stub
		return 0;
	}
	public long pexpireAt(Object key, long unixTime) {
		// TODO Auto-generated method stub
		return 0;
	}

	public long persist(Object key) {
		Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
		WritePolicy wp = new WritePolicy();
		wp.expiration = -1;
		this.asClient.touch(wp, asKey);
		return 1L;
	}

	public long dbSize() {
		// ns_name=test:set_name=tweets:n_objects=68763:set-stop-write-count=0:set-evict-hwm-count=0:set-enable-xdr=use-default:set-delete=false;
		Pattern pattern = Pattern.compile("ns_name=" + this.namespace + ":set_name=" + this.redisSet + ":n_objects=(\\d+)");
		String[] infoStrings = infoAll(this.infoPolicy, this.asClient, "sets");
		long size = 0;
		for (String info : infoStrings){
			Matcher matcher = pattern.matcher(info);
			while (matcher.find()){
				size += Long.parseLong(matcher.group(1));
			}
		}
		return size;
	}

	public String echo(String message) {
		return message;
	}

	public Long ttl(Object key) {
		try {
			Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
			Record record = this.asClient.getHeader(this.policy, asKey);
			long now = (System.currentTimeMillis() - AS_TIME_OFFSET) / 1000;
			long exp = record.expiration;
			long TTL = (exp - now);
			return TTL;
		} catch (AerospikeException e){
			if (e.getResultCode() == ResultCode.KEY_NOT_FOUND_ERROR){
				return -2L;
			} else {
				throw e;
			}
		}
	}




	public long pttl(String key) {
		return ttl(key) * 1000;
	}


	public String ping() {
		if (this.asClient.isConnected())
			return "PONG";
		else 
			return null;
	}


	public long incr(Object key) {
		return incrBy(key, 1);
	}


	public long incrBy(Object key, long increment) {
		Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
		Bin keyBin = new Bin(this.keyBin , key);
		Bin addBin = new Bin(this.redisBin, Value.get(increment));
		WritePolicy wp = new WritePolicy();
		wp.recordExistsAction = RecordExistsAction.UPDATE;
		Record record = this.asClient.operate(wp, asKey, Operation.put(keyBin), Operation.add(addBin), Operation.get(this.redisBin));
		return record.getInt(this.redisBin);
	}

	public double incrByFloat(Object key, double value) {
		Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
		Object ret = this.asClient.execute(this.writePolicy, asKey, "redis", "INCRBYFLOAT", Value.get(this.redisBin), Value.get(value));
		return (Double) ret;
	}


	public long decr(Object key) {
		return decrBy(key, 1);
	}


	public long decrBy(Object key, long i) {
		Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
		Bin keyBin = new Bin(this.keyBin , key);
		Bin addBin = new Bin(this.redisBin, -i);
		WritePolicy wp = new WritePolicy();
		wp.recordExistsAction = RecordExistsAction.UPDATE;
		Record record = this.asClient.operate(wp, asKey, Operation.put(keyBin), Operation.add(addBin), Operation.get(this.redisBin));
		return record.getInt(this.redisBin);
	}


	public Object getSet(Object key, Object value) {
		Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
		return this.asClient.execute(batchPolicy, asKey, "redis", "GETSET", Value.get(this.redisBin), Value.get(value));
	}


	public long append(Object key, Object value) {
		Key asKey = new Key(this.namespace, this.redisSet, Value.get(key));
		Bin keyBin = new Bin(this.keyBin , key);
		Bin appendBin = new Bin(this.redisBin, Value.get(value));
		WritePolicy wp = new WritePolicy();
		wp.recordExistsAction = RecordExistsAction.UPDATE;
		Record record = this.asClient.operate(wp, asKey, Operation.put(keyBin), Operation.append(appendBin), Operation.get(this.redisBin));
		return ((String)record.getValue(this.redisBin)).length();
	}

	public String getRange(String key, long startOffset, long endOffset) {
		String result = get(key);
		return result.substring((int)startOffset, (int)endOffset+1);
	}


	public Object substr(String key, long startOffset, long endOffset) {
		String result = get(key);
		return result.substring((int)startOffset, (int)endOffset+1);
	}


	public Long strlen(String key) {
		String result = get(key);
		return (long) result.length();
	}

/*
 * List operations
 */
	public long rpush(String key, String value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "RPUSH", Value.get(this.redisBin), Value.get(value));
	}


	public long lpush(String key, String value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "LPUSH", Value.get(this.redisBin), Value.get(value));
	}


	public Long llen(String key) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "LLEN", Value.get(this.redisBin));
	}


	public List<String> lrange(String key, int low, int high) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (List<String>) this.asClient.execute(batchPolicy, asKey, "redis", "LRANGE", Value.get(this.redisBin), Value.get(low), Value.get(high));
	}


	public String ltrim(String key, int start, int stop) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (String) this.asClient.execute(batchPolicy, asKey, "redis", "LTRIM", Value.get(this.redisBin), Value.get(start), Value.get(stop));
	}


	public String lset(String key, int index, String value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (String) this.asClient.execute(batchPolicy, asKey, "redis", "LSET", Value.get(this.redisBin), Value.get(index), Value.get(value));
	}


	public Object lindex(String key, int index) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return this.asClient.execute(batchPolicy, asKey, "redis", "LINDEX", Value.get(this.redisBin), Value.get(index));
	}


	public Long lrem(String key, int index, String value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "LREM", Value.get(this.redisBin), Value.get(index), Value.get(value));
	}


	public String lpop(String key) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (String) this.asClient.execute(batchPolicy, asKey, "redis", "LPOP", Value.get(this.redisBin), Value.get(1));
	}


	public String rpop(String key) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (String) this.asClient.execute(batchPolicy, asKey, "redis", "RPOP", Value.get(this.redisBin), Value.get(1));
	}


	public String rpoplpush(String key, String value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (String) this.asClient.execute(batchPolicy, asKey, "redis", "RPOPLPUSH", Value.get(this.redisBin), Value.get(value));
	}


	public long lpushx(String key, String value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "LPUSHX", Value.get(this.redisBin), Value.get(value));
	}


	public long rpushx(String key, String value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "RPUSHX", Value.get(this.redisBin), Value.get(value));
	}


	public long linsert(String key, LIST_POSITION position, String piviot,
			String value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "LINSERT", Value.get(this.redisBin), 
				Value.get(position), Value.get(piviot), Value.get(value));
	}
/*
 * Hash (Map) operations
 */

	public long hset(String key, String field, String value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		Object result =  this.asClient.execute(batchPolicy, asKey, "redis", "HSET", Value.get(this.redisBin), 
				Value.get(field), Value.get(value));
		return ((Integer)result).longValue();
	}


	public Object hget(String key, String field) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return this.asClient.execute(batchPolicy, asKey, "redis", "HGET", Value.get(this.redisBin), 
				Value.get(field));
	}


	public long hsetnx(String key, String field, String value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "HSETNX", Value.get(this.redisBin), 
				Value.get(field), Value.get(value));
	}


	public String hmset(String key, Map<String, String> hash) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (String) this.asClient.execute(batchPolicy, asKey, "redis", "HMSET", Value.get(this.redisBin), 
				Value.get(hash));
	}


	@SuppressWarnings("unchecked")
	public List<String> hmget(String key, String ...fields) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (List<String>) this.asClient.execute(batchPolicy, asKey, "redis", "HMGET", Value.get(this.redisBin), 
				Value.getAsList(new ArrayList<String>(Arrays.asList(fields))));
	}


	public long hincrBy(String key, String field, long increment) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "HINCRBY", Value.get(this.redisBin), 
				Value.get(field), Value.get(increment));
	}


	public boolean hexists(String key, String field) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Boolean) this.asClient.execute(batchPolicy, asKey, "redis", "HEXISTS", Value.get(this.redisBin), 
				Value.get(field));
	}


	public Long hdel(String key, String field) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "HDEL", Value.get(this.redisBin), 
				Value.get(field));
	}


	public Long hlen(String key) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Long) this.asClient.execute(batchPolicy, asKey, "redis", "HLEN", Value.get(this.redisBin));
	}


	@SuppressWarnings("unchecked")
	public Set<String> hkeys(String key) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Set<String>) this.asClient.execute(batchPolicy, asKey, "redis", "HKEYS", Value.get(this.redisBin));
	}


	@SuppressWarnings("unchecked")
	public List<String> hvals(String key) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (List<String>) this.asClient.execute(batchPolicy, asKey, "redis", "HVALS", Value.get(this.redisBin));
	}


	public Map<String, String> hgetAll(String key) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Map<String, String>) this.asClient.execute(batchPolicy, asKey, "redis", "HGETALL", Value.get(this.redisBin));
	}


	public Double hincrByFloat(String key, String field, double value) {
		Key asKey = new Key(this.namespace, this.redisSet, key);
		return (Double) this.asClient.execute(batchPolicy, asKey, "redis", "HINCRBYFLOAT", Value.get(this.redisBin), 
				Value.get(field), Value.get(value));
	}





}
