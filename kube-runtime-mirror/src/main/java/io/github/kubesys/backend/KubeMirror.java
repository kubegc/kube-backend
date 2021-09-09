/**
 * Copyright (2019, ) Institute of Software, Chinese Academy of Sciences
 */
package io.github.kubesys.backend;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.kubesys.kubeclient.KubernetesCRDWacther;
import io.github.kubesys.kubeclient.KubernetesClient;
import io.github.kubesys.kubeclient.KubernetesConstants;
import io.github.kubesys.kubeclient.core.KubernetesRuleBase;


/**
 * @author wuheng@iscas.ac.cn
 * @since  2.0.0
 */
public class KubeMirror {

	/**
	 * m_logger
	 */
	public static final Logger m_logger = Logger.getLogger(KubeMirror.class.getName());

	/**
	 * sources
	 */
	public Set<String> sources = new HashSet<>();

	/**
	 * sql client
	 */
	protected SQLMapper sqlMapper;
	
	/**
	 * msgMapper
	 */
	protected MessageMapper msgMapper;
	
	
	/**
	 * threads
	 */
	protected final Map<String, Thread> watchers = new HashMap<>();
	
	/****************************************************************************
	 * 
	 * 
	 *                         Insert, Update, Delete objects
	 * 
	 * 
	 *****************************************************************************/
	
	public KubeMirror(KubernetesClient kubeClient) throws Exception {
		try {
			this.sqlMapper = new SQLMapper(kubeClient);
			this.msgMapper = new MessageMapper();
		} catch (Exception ex) {
			m_logger.severe(ex.toString());
			System.exit(1);
		}
	}


	/**
	 * @return                               mirror
	 * @throws Exception                     exception
	 */
	public KubeMirror fromSources() throws Exception {
		List<String> fullKinds = new ArrayList<>();
		try {
			KubernetesRuleBase ruleBase = sqlMapper.getKubeClient().getAnalyzer()
					.getConvertor().getRuleBase();
			for (List<String> fullkinds : ruleBase.getFullKinds().values()) {
				for (String fullKind : fullkinds) {
					fullKinds.add(fullKind);
				}
			}
			return fromSource(fullKinds.toArray(new String[] {}));
		} catch (Exception ex) {
			m_logger.severe(ex.toString());
			System.exit(1);
		}

		return this;
	}
	
	/**
	 * @param  fullKind                      kind
	 * @return                               mirror
	 * @throws Exception                     exception
	 */
	public KubeMirror fromSource(String fullKind) throws Exception {
		String[] fullkinds = new String[1];
		fullkinds[0] = fullKind;
		return fromSource(fullkinds);
	}
	
	/**
	 * @param  fullkinds                     kinds
	 * @return                               mirror
	 * @throws Exception                     exception
	 */
	public KubeMirror fromSource(String[] fullkinds) throws Exception {
		for (String fullkind : fullkinds) {
			this.sources.add(fullkind);
			
			String table = sqlMapper.getKubeClient().getAnalyzer()
					.getConvertor().getRuleBase().getName(fullkind);

			if (table.contains("/") || table.contains("-")) {
				continue;
			}
			
			deleteTableIfExit(table);
			sqlMapper.createTable(table);
			
			KubeUtils.createMeatadata(getKubeClient(), fullkind);
		}
		return this;
	}
	
	/**
	 * @throws Exception                    exception
	 */
	public void start() throws Exception {
		fromSources().toTargets();
	}
	
	/**
	 * @param  kind                         kind
	 * @throws Exception                    exception
	 */
	public void start(String kind) throws Exception {
		fromSource(kind).toTargets();
	}
	
	/**
	 * @param  kinds                        kinds
	 * @throws Exception                    exception
	 */
	public void start(String[] kinds) throws Exception {
		fromSource(kinds).toTargets();
	}


	/**
	 * @return                              mirror
	 * @throws Exception                    exception
	 */
	protected KubeMirror toTargets() throws Exception {
		for (String kind : sources) {
			try {
				doWatcher(kind);
			} catch (Exception ex) {
				m_logger.severe(ex.toString());
				continue;
			}
		}
		return this;
	}

	/**
	 * @param kind                          kind
	 * @throws Exception                    exception
	 */
	protected void doWatcher(String kind) throws Exception {
		
		KubernetesRuleBase ruleBase = sqlMapper.getKubeClient().getAnalyzer().getConvertor().getRuleBase();
		String table = ruleBase.getName(kind);

		try {
			m_logger.info("starting Watcher " + kind);
			SourceToSink watcher = new SourceToSink(kind, table, this, new MessageMapper());
			watchers.put(table, sqlMapper.getKubeClient().watchResources(kind, watcher));
			m_logger.info("Watcher " + kind + " is working");
		} catch (Exception ex) {
			m_logger.info("fail to start Watcher " + kind);
			m_logger.severe(ex.toString());
		}
	}

	/**
	 * @param table                   table
	 * @throws Exception              exception
	 */
	protected void deleteTableIfExit(String table) throws Exception {
		if (sqlMapper.checkTable(table)) {
			sqlMapper.dropTable(table);
		}
	}
	
	/**
	 * @param kind                          kind
	 * @throws Exception                    exception
	 */
	@SuppressWarnings("deprecation")
	protected void stopWatcher(String kind) throws Exception {
		String table = sqlMapper.getKubeClient().getAnalyzer().getConvertor().getRuleBase().getName(kind);
		if (sqlMapper.checkTable(table)) {
			sqlMapper.dropTable(table);
		} 
		watchers.get(table).stop();
		watchers.remove(table);
	}
	
	
	public void addSource(String kind) {
		if (!sources.contains(kind)) {
			sources.add(kind);
			try {
				doWatcher(kind);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	public void deleteSource(String kind) {
		if (sources.contains(kind)) {
			sources.remove(kind);
			try {
				stopWatcher(kind);
			} catch (Exception e) {
			}
		}
	}

	public boolean beenWatched(String kind) {
		return watchers.containsKey(sqlMapper.getKubeClient()
				.getAnalyzer().getConvertor().getRuleBase().getName(kind));
	}

	public KubernetesClient getKubeClient() {
		return sqlMapper.getKubeClient();
	}

	public SQLMapper getSqlClient() {
		return sqlMapper;
	}
	
	
	/**
	 * @author wuheng
	 * @since 2019.4.20
	 */
	public static class SourceToSink extends IncludedAMQPWatcher {

		public static final Logger m_logger = Logger.getLogger(SourceToSink.class.getName());

		/**
		 * kind
		 */
		protected final String kind;

		/**
		 * table
		 */
		protected final String table;

		/**
		 * sqlClient
		 */
		protected final KubeMirror kubeMirror;
		
		/**
		 * crdWatcher
		 */
		protected final KubernetesCRDWacther crdWatcher;
		
		public SourceToSink(String kind, String table, KubeMirror kubeMirror, MessageMapper msgMapper) throws Exception {
			super(kubeMirror.getKubeClient(), msgMapper);
			this.kind = kind;
			this.table = table;
			this.kubeMirror = kubeMirror;
			this.crdWatcher = new KubernetesCRDWacther(kubeMirror.getKubeClient());
		}

		@Override
		public void doAdded(JsonNode json) {
			super.doAdded(json);
			try {
				kubeMirror.getSqlClient().insertObject(table, KubeUtils.getName(json),
						KubeUtils.getNamespace(json),
						getGroup(json),
						createDateTime(json),
						currentDateTime(),
//						json.toPrettyString());
						KubeUtils.getJsonWithoutAnotation(json));
				m_logger.info("insert object  " + json + " successfully.");
				
				if ("CustomResourceDefinition".equals(json.get("kind").asText())) {
					this.crdWatcher.doAdded(json);
					JsonNode spec = json.get(KubernetesConstants.KUBE_SPEC);
					JsonNode names = spec.get(KubernetesConstants.KUBE_SPEC_NAMES);
					String kind = names.get(KubernetesConstants.KUBE_SPEC_NAMES_KIND).asText();
					String group = spec.get("group").asText();
					kubeMirror.addSource(group + "." + kind);
					KubeUtils.createMeatadata(kubeMirror.getKubeClient(), group + "." + kind);
				}
				
			} catch (Exception e) {
				m_logger.severe("fail to insert object :" + json + " in table " + table + ". Becasue: " + e);
			}
			
		}

		@Override
		public void doModified(JsonNode json) {
			super.doModified(json);
			try {
				kubeMirror.getSqlClient().updateObject(table, KubeUtils.getName(json),
						KubeUtils.getNamespace(json),
						getGroup(json),
						currentDateTime(),
						KubeUtils.getJsonWithoutAnotation(json));
				m_logger.info("update object  " + json + " successfully.");
			} catch (Exception e) {
				m_logger.severe("fail to update object :" + e);
			}
			
		}

		@Override
		public void doDeleted(JsonNode json) {
			super.doDeleted(json);
			try {
				kubeMirror.getSqlClient().deleteObject(table, KubeUtils.getName(json),
						KubeUtils.getNamespace(json),
						getGroup(json),
						KubeUtils.getJsonWithoutAnotation(json));
				m_logger.info("delete object  " + json + " successfully.");
				
				if ("CustomResourceDefinition".equals(json.get("kind").asText())) {
					this.crdWatcher.doDeleted(json);
					JsonNode spec = json.get(KubernetesConstants.KUBE_SPEC);
					JsonNode names = spec.get(KubernetesConstants.KUBE_SPEC_NAMES);
					String kind = names.get(KubernetesConstants.KUBE_SPEC_NAMES_KIND).asText();
					String group = spec.get("group").asText();
					kubeMirror.deleteSource(group + "." + kind);
					kubeMirror.getKubeClient().deleteResource("doslab.io.Metadata", group + "." + kind);
				}
				
			} catch (Exception e) {
				m_logger.severe("fail to delete object :" + e);
			}
			
		}

		@Override
		public void doClose() {
			try {
				client.watchResources(kind, new SourceToSink(
						kind, table, kubeMirror, msgClient));
			} catch (Exception e) {
				System.exit(1);
			}
		}
		
		public static String getGroup (JsonNode json) {
			String apiVersion = json.get("apiVersion").asText();
			int idx = apiVersion.indexOf("/");
			return (idx == -1) ? "" : apiVersion.substring(0, idx);
		}
		
		
	}
	
	public static String createDateTime(JsonNode json) {
		String date = json.get("metadata").get("creationTimestamp").asText();
		return date.replace("T", " ").substring(0, date.length() - 1);
	}
	
	public static String currentDateTime() {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		return sdf.format(new Date());
	}
}
