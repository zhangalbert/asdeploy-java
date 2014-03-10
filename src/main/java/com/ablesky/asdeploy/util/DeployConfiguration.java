package com.ablesky.asdeploy.util;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;



public class DeployConfiguration {
	
	private static DeployConfiguration INSTANCE;
	
	public static DeployConfiguration getInstance() {
		if(INSTANCE == null) {
			INSTANCE =  new DeployConfiguration();
		}
		return DeployConfiguration.INSTANCE;
	}
	
	public static final String NEED_SEND_EMAIL_YES = "y";
	public static final String NEED_SEND_EMAIL_NO = "n";
	
	// 版本
	private String version = "1.4";
	// 根路径
	private String rootPath = "/d/content/web-app-bak/";
	// 上传文件根路径
	private String itemRootPath = rootPath + "ableskyapps/";
	// 脚本根路径
	private String scriptRootPath = rootPath + "deployment/";
	// 服务器的hostname
	private String hostname;
	// 环境名称
	private String environment;
	// 是否需要发邮件(测试环境均为no)
	private String needSendEmail;
	
	public DeployConfiguration() {
		hostname = DeployUtil.getHostname();
		environment = parseEnvironment(hostname);
		needSendEmail = NEED_SEND_EMAIL_NO;
	}

	public String getVersion() {
		return version;
	}

	public String getRootPath() {
		return rootPath;
	}

	public String getItemRootPath() {
		return itemRootPath;
	}

	public String getScriptRootPath() {
		return scriptRootPath;
	}

	public String getHostname() {
		return hostname;
	}

	public String getEnvironment() {
		return environment;
	}
	
	public String getNeedSendEmail() {
		return needSendEmail;
	}

	public static String parseEnvironment(String hostname) {
		if(SystemUtils.IS_OS_WINDOWS 
				|| StringUtils.isBlank(hostname) 
				|| "unknown".equalsIgnoreCase(hostname)) {
			return "DEVELOPMENT";
		}
		if(hostname.contains(".at1.")) {
			return "ALPHA";
		}
		if(hostname.contains(".bt1.")) {
			return "BETA";
		}
		if(hostname.contains(".ot1.")) {
			return "OMEGA";
		}
		if(hostname.contains(".gt1.")) {
			return "GAMMA";
		}
		return "DEVELOPMENT";
	}
	
}
