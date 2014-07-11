/**
 * 
 */
package com.dianping.pigeon.console.servlet;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import com.dianping.pigeon.config.ConfigConstants;
import com.dianping.pigeon.config.ConfigManager;
import com.dianping.pigeon.console.Utils;
import com.dianping.pigeon.console.domain.Service;
import com.dianping.pigeon.console.domain.ServiceMethod;
import com.dianping.pigeon.extension.ExtensionLoader;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.remoting.ServiceFactory;
import com.dianping.pigeon.remoting.common.process.ServiceStatusChecker;
import com.dianping.pigeon.remoting.invoker.process.ServiceInvokerStatusChecker;
import com.dianping.pigeon.remoting.provider.ProviderBootStrap;
import com.dianping.pigeon.remoting.provider.Server;
import com.dianping.pigeon.remoting.provider.config.ProviderConfig;
import com.dianping.pigeon.remoting.provider.config.ServerConfig;
import com.dianping.pigeon.remoting.provider.service.PublishStatus;
import com.dianping.pigeon.remoting.provider.service.ServiceProviderFactory;
import com.dianping.pigeon.util.RandomUtils;

import freemarker.cache.ClassTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapper;
import freemarker.template.Template;
import freemarker.template.TemplateException;

/**
 * @author sean.wang
 * @since Jul 16, 2012
 */
public class ServiceServlet extends HttpServlet {
	private static final long serialVersionUID = -2703014417332812558L;

	private Set<String> ingoreMethods = new HashSet<String>();

	protected int port;

	protected ServerConfig serverConfig;

	protected Object model;

	protected final Logger logger = LoggerLoader.getLogger(this.getClass());

	protected static ConfigManager configManager = ExtensionLoader.getExtension(ConfigManager.class);

	private static final String VALID_PACKAGES = configManager.getStringValue("pigeon.service.packages",
			"com.dianping,com.dp");

	private static final ServiceStatusChecker serviceInvokerStatusChecker = new ServiceInvokerStatusChecker();

	protected static String token;

	{
		Method[] objectMethodArray = Object.class.getMethods();
		for (Method method : objectMethodArray) {
			ingoreMethods.add(method.getName() + ":" + Arrays.toString(method.getParameterTypes()));
		}

	}

	private final Configuration cfg = new Configuration();

	{
		cfg.setObjectWrapper(new DefaultObjectWrapper());
		ClassTemplateLoader templateLoader = new ClassTemplateLoader(ServiceServlet.class, "/");
		cfg.setTemplateLoader(templateLoader);
	}

	public ServiceServlet(ServerConfig serverConfig, int port) {
		this.serverConfig = serverConfig;
		this.port = port;
	}

	public static String getToken() {
		return token;
	}

	public static void setToken(String token) {
		ServiceServlet.token = token;
	}

	public Map<String, ProviderConfig<?>> getServiceProviders() {
		return ServiceFactory.getAllServiceProviders();
	}

	protected void initServicePage(HttpServletRequest request) {
		ServicePage page = new ServicePage();
		Collection<Server> servers = ProviderBootStrap.getServersMap().values();
		StringBuilder ports = new StringBuilder();
		for (Server server : servers) {
			if (server.isStarted()) {
				ports.append(server.getPort()).append("/");
			}
		}
		if (ports.length() > 0) {
			ports.deleteCharAt(ports.length() - 1);
			page.setPort(ports.toString());
		}
		page.setHttpPort(this.port);
		Map<String, ProviderConfig<?>> serviceProviders = getServiceProviders();
		for (Entry<String, ProviderConfig<?>> entry : serviceProviders.entrySet()) {
			String serviceName = entry.getKey();
			ProviderConfig<?> providerConfig = entry.getValue();
			Service s = new Service();
			s.setName(serviceName);
			Class<?> beanClass = providerConfig.getService().getClass();
			int idxCglib = beanClass.getName().indexOf("$$EnhancerByCGLIB");
			if (idxCglib != -1) {
				try {
					beanClass = Class.forName(beanClass.getName().substring(0, idxCglib));
				} catch (ClassNotFoundException e) {
					throw new IllegalStateException("Failed to export remote service class " + beanClass.getName(), e);
				}
			}
			s.setType(beanClass);
			s.setPublished(providerConfig.isPublished() + "");
			Map<String, Method> allMethods = new HashMap<String, Method>();
			Class<?>[] ifaces = beanClass.getInterfaces();
			String[] validPackages = VALID_PACKAGES.split(",");
			for (Class<?> iface : ifaces) {
				String facename = iface.getName();
				logger.info("service interface:" + facename);
				boolean isValid = false;
				for (String pkg : validPackages) {
					if (facename.startsWith(pkg)) {
						isValid = true;
						break;
					}
				}
				if (isValid) {
					Method[] methods = iface.getMethods();
					for (Method method : methods) {
						String key = method.getName() + ":" + Arrays.toString(method.getParameterTypes());
						if (!ingoreMethods.contains(key)) {
							allMethods.put(key, method);
						}
					}
				}
			}
			for (Entry<String, Method> methodEntry : allMethods.entrySet()) {
				Method method = methodEntry.getValue();
				s.addMethod(new ServiceMethod(method.getName(), method.getParameterTypes(), method.getReturnType()));
			}
			page.addService(s);
		}
		setStatus(page, serviceProviders.isEmpty());
		page.setDirect(request.getParameter("direct"));
		page.setEnvironment(configManager.getEnv());
		page.setGroup(configManager.getGroup());
		page.setServiceWeights(ServiceProviderFactory.getServerWeight());
		this.model = page;
	}

	private void setStatus(ServicePage page, boolean isClientSide) {
		String error = serviceInvokerStatusChecker.check();
		if (!StringUtils.isBlank(error)) {
			page.setError(error);
			page.setStatus("error");
		}
		if (isClientSide) {// client-side
			// set published
			page.setPublished("none");
			// set status
			if (!"error".equals(page.getStatus())) {
				page.setStatus("ok");
			}
		} else {// server-side
			// set published
			PublishStatus status = ServiceProviderFactory.getPublishStatus();
			if (status.equals(PublishStatus.PUBLISHED) || status.equals(PublishStatus.WARMINGUP)
					|| status.equals(PublishStatus.WARMEDUP)) {
				page.setPublished("true");
			} else {
				page.setPublished("false");
			}
			// set status
			if (!"error".equals(page.getStatus())) {
				if ("true".equals(page.getPublished())) {
					page.setStatus("ok");
				} else {
					page.setStatus(status.toString());
				}
			}
		}
	}

	public String getView() {
		return "Service.ftl";
	}

	public String getContentType() {
		return "text/html; charset=UTF-8";
	}

	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setContentType(getContentType());
		response.setStatus(HttpServletResponse.SC_OK);

		generateView(request, response);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doGet(req, resp);
	}

	protected void generateView(HttpServletRequest request, HttpServletResponse response) throws IOException,
			ServletException {
		Template temp = cfg.getTemplate(getView());
		initServicePage(request);
		/*
		 * if (this.model == null) { synchronized (this) { if (this.model ==
		 * null) { initServicePage(); } } }
		 */
		try {
			temp.process(this.model, response.getWriter());
		} catch (TemplateException e) {
			throw new ServletException(e);
		}
		if (ConfigConstants.ENV_PRODUCT.equalsIgnoreCase(configManager.getEnv())) {
			String token = RandomUtils.newRandomString(6);
			setToken(token);
			logger.info("current verification code:" + token + ", from " + Utils.getIpAddr(request));
		}
	}

}
