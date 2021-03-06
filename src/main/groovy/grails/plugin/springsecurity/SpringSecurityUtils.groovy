/* Copyright 2006-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugin.springsecurity

import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY

import javax.servlet.Filter
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpSession

import org.apache.commons.lang.StringEscapeUtils
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserCache
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.WebAttributes
import org.springframework.security.web.authentication.switchuser.SwitchUserFilter
import org.springframework.security.web.authentication.switchuser.SwitchUserGrantedAuthority
import org.springframework.security.web.savedrequest.SavedRequest
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.util.StringUtils
import org.springframework.web.multipart.MultipartHttpServletRequest

import grails.core.GrailsApplication
import grails.plugin.springsecurity.web.SecurityRequestHolder
import grails.plugin.springsecurity.web.filter.DebugFilter
import grails.util.Environment
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Helper methods.
 *
 * @author <a href='mailto:burt@burtbeckwith.com'>Burt Beckwith</a>
 */
@CompileStatic
@Slf4j
final class SpringSecurityUtils {

	private static final String MULTIPART_HTTP_SERVLET_REQUEST_KEY = MultipartHttpServletRequest.name

	private static ConfigObject _securityConfig
	private static GrailsApplication application

	/** Ordered filter names. Plugins add or remove them, and can be overridden by config. */
	static Map<Integer, String> orderedFilters = [:]

	/** Set by SpringSecurityCoreGrailsPlugin contains the actual filter beans in order. */
	static SortedMap<Integer, Filter> configuredOrderedFilters = new TreeMap<Integer, Filter>()

	/** Authentication provider names. Plugins add or remove them, and can be overridden by config. */
	static List<String> providerNames = []

	/** Logout handler names. Plugins add or remove them, and can be overridden by config. */
	static List<String> logoutHandlerNames = []

	/** AfterInvocationProvider names. Plugins add or remove them, and can be overridden by config. */
	static List<String> afterInvocationManagerProviderNames = []

	/** Voter names. Plugins add or remove them and can be overridden by config. */
	static List<String> voterNames = []

	// HttpSessionRequestCache.SAVED_REQUEST is package-scope
	public static final String SAVED_REQUEST = 'SPRING_SECURITY_SAVED_REQUEST' // TODO use requestCache

	// UsernamePasswordAuthenticationFilter.SPRING_SECURITY_LAST_USERNAME_KEY is deprecated
	public static final String SPRING_SECURITY_LAST_USERNAME_KEY = 'SPRING_SECURITY_LAST_USERNAME'

	// AbstractAuthenticationTargetUrlRequestHandler.DEFAULT_TARGET_PARAMETER was removed
	public static final String DEFAULT_TARGET_PARAMETER = 'spring-security-redirect'

	/** Default value for the name of the Ajax header. */
	public static final String AJAX_HEADER = 'X-Requested-With'

	/**
	 * Used to ensure that all authenticated users have at least one granted authority to work
	 * around Spring Security code that assumes at least one. By granting this non-authority,
	 * the user can't do anything but gets past the somewhat arbitrary restrictions.
	 */
	public static final String NO_ROLE = 'ROLE_NO_ROLES'

	public static final String XML_HTTP_REQUEST = 'XMLHttpRequest'

	private SpringSecurityUtils() {
		// static only
	}

	/**
	 * Set at startup by plugin.
	 * @param app the application
	 */
	static void setApplication(GrailsApplication app) {
		application = app
		initializeContext()
	}

	/**
	 * Extract the role names from authorities.
	 * @param authorities the authorities (a collection or array of {@link GrantedAuthority}).
	 * @return the names
	 */
	static Set<String> authoritiesToRoles(authorities) {
		Set<String> roles = new HashSet<String>()
		for (authority in ReflectionUtils.asList(authorities)) {
			String authorityName = ((GrantedAuthority)authority).authority
			assert authorityName != null,
				"Cannot process GrantedAuthority objects which return null from getAuthority() - attempting to process $authority"
			roles << authorityName
		}

		roles
	}

	/**
	 * Get the current user's authorities.
	 * @return a list of authorities (empty if not authenticated).
	 */
	static Collection<GrantedAuthority> getPrincipalAuthorities() {
		Authentication authentication = getAuthentication()
		if (!authentication) {
			return Collections.emptyList()
		}

		Collection<? extends GrantedAuthority> authorities = authentication.authorities
		if (authorities == null) {
			return Collections.emptyList()
		}

		// remove the fake role if it's there
		Collection<GrantedAuthority> copy = ([] + authorities) as Collection
		for (Iterator<GrantedAuthority> iter = copy.iterator(); iter.hasNext();) {
			if (NO_ROLE == iter.next().authority) {
				iter.remove()
			}
		}

		copy
	}

	/**
	 * Split the role names and create {@link GrantedAuthority}s for each.
	 * @param roleNames comma-delimited role names
	 * @return authorities (possibly empty)
	 */
	static List<GrantedAuthority> parseAuthoritiesString(String roleNames) {
		List<GrantedAuthority> requiredAuthorities = []
		for (String auth in StringUtils.commaDelimitedListToStringArray(roleNames)) {
			auth = auth.trim()
			if (auth) {
				requiredAuthorities << new SimpleGrantedAuthority(auth)
			}
		}

		requiredAuthorities
	}

	/**
	 * Find authorities in <code>granted</code> that are also in <code>required</code>.
	 * @param granted the granted authorities (a collection or array of {@link GrantedAuthority}).
	 * @param required the required authorities (a collection or array of {@link GrantedAuthority}).
	 * @return the authority names
	 */
	static Set<String> retainAll(granted, required) {
		Set<String> grantedRoles = authoritiesToRoles(granted)
		grantedRoles.retainAll authoritiesToRoles(required)
		grantedRoles
	}

	/**
	 * Check if the current user has all of the specified roles.
	 * @param roles a comma-delimited list of role names
	 * @return <code>true</code> if the user is authenticated and has all the roles
	 */
	static boolean ifAllGranted(String roles) {
		ifAllGranted parseAuthoritiesString(roles)
 	}

	static boolean ifAllGranted(Collection<? extends GrantedAuthority> roles) {
		authoritiesToRoles(findInferredAuthorities(principalAuthorities)).containsAll authoritiesToRoles(roles)
	}

	/**
	 * Check if the current user has none of the specified roles.
	 * @param roles a comma-delimited list of role names
	 * @return <code>true</code> if the user is authenticated and has none the roles
	 */
	static boolean ifNotGranted(String roles) {
		ifNotGranted parseAuthoritiesString(roles)
	}

	static boolean ifNotGranted(Collection<? extends GrantedAuthority> roles) {
		!retainAll(findInferredAuthorities(principalAuthorities), roles)
	}

	/**
	 * Check if the current user has any of the specified roles.
	 * @param roles a comma-delimited list of role names
	 * @return <code>true</code> if the user is authenticated and has any the roles
	 */
	static boolean ifAnyGranted(String roles) {
		ifAnyGranted parseAuthoritiesString(roles)
	}

	static boolean ifAnyGranted(Collection<? extends GrantedAuthority> roles) {
		retainAll findInferredAuthorities(principalAuthorities), roles
	}

	/**
	 * Parse and load the security configuration.
	 * @return the configuration
	 */
	static synchronized ConfigObject getSecurityConfig() {
		if (_securityConfig == null) {
			log.trace 'Building security config since there is no cached config'
			reloadSecurityConfig()
		}

		_securityConfig
	}

	/**
	 * For testing only.
	 * @param config the config
	 */
	static void setSecurityConfig(ConfigObject config) {
		_securityConfig = config
	}

	/** Reset the config for testing or after a dev mode Config.groovy change. */
	static synchronized void resetSecurityConfig() {
		_securityConfig = null
		log.trace 'reset security config'
	}

	/**
	 * Allow a secondary plugin to add config attributes.
	 * @param className the name of the config class.
	 */
	static synchronized void loadSecondaryConfig(String className) {
		mergeConfig securityConfig, className
		log.trace 'loaded secondary config {}', className
	}

	/** Force a reload of the security configuration. */
	static void reloadSecurityConfig() {
		mergeConfig ReflectionUtils.securityConfig, 'DefaultSecurityConfig'
		log.trace 'reloaded security config'
	}

	/**
	 * Check if the request was triggered by an Ajax call.
	 * @param request the request
	 * @return <code>true</code> if Ajax
	 */
	static boolean isAjax(HttpServletRequest request) {

		String ajaxHeaderName = (String)ReflectionUtils.getConfigProperty('ajaxHeader')

		// check the current request's headers
		if (XML_HTTP_REQUEST == request.getHeader(ajaxHeaderName)) {
			return true
		}

		def ajaxCheckClosure = ReflectionUtils.getConfigProperty('ajaxCheckClosure')
		if (ajaxCheckClosure instanceof Closure) {
			def result = ajaxCheckClosure(request)
			if (result instanceof Boolean && result) {
				return true
			}
		}

		// look for an ajax=true parameter
		if ('true' == request.getParameter('ajax')) {
			return true
		}

		// process multipart requests
		MultipartHttpServletRequest multipart = (MultipartHttpServletRequest)request.getAttribute(MULTIPART_HTTP_SERVLET_REQUEST_KEY)
		if ('true' == multipart?.getParameter('ajax')) {
			return true
		}

		// check the SavedRequest's headers
		HttpSession httpSession = request.getSession(false)
		if (httpSession) {
			SavedRequest savedRequest = (SavedRequest)httpSession.getAttribute(SAVED_REQUEST)
			if (savedRequest) {
				return savedRequest.getHeaderValues(ajaxHeaderName).contains(MULTIPART_HTTP_SERVLET_REQUEST_KEY)
			}
		}

		false
	}

	/**
	 * Register a provider bean name.
	 *
	 * Note - only for use by plugins during bean building.
	 *
	 * @param beanName the Spring bean name of the provider
	 */
	static void registerProvider(String beanName) {
		providerNames.add 0, beanName
		log.trace 'Registered bean "{}" as a provider', beanName
	}

	/**
	 * Register a logout handler bean name.
	 *
	 * Note - only for use by plugins during bean building.
	 *
	 * @param beanName the Spring bean name of the handler
	 */
	static void registerLogoutHandler(String beanName) {
		logoutHandlerNames.add 0, beanName
		log.trace 'Registered bean "{}" as a logout handler', beanName
	}

	/**
	 * Register an AfterInvocationProvider bean name.
	 *
	 * Note - only for use by plugins during bean building.
	 *
	 * @param beanName the Spring bean name of the provider
	 */
	static void registerAfterInvocationProvider(String beanName) {
		afterInvocationManagerProviderNames.add 0, beanName
		log.trace 'Registered bean "{}" as an AfterInvocationProvider', beanName
	}

	/**
	 * Register a voter bean name.
	 *
	 * Note - only for use by plugins during bean building.
	 *
	 * @param beanName the Spring bean name of the voter
	 */
	static void registerVoter(String beanName) {
		voterNames.add 0, beanName
		log.trace 'Registered bean "{}" as a voter', beanName
	}

	/**
	 * Register a filter bean name in a specified position in the chain.
	 *
	 * Note - only for use by plugins during bean building - to register at runtime
	 * (preferably in BootStrap) use <code>clientRegisterFilter</code>.
	 *
	 * @param beanName the Spring bean name of the filter
	 * @param position the position
	 */
	static void registerFilter(String beanName, SecurityFilterPosition position) {
		registerFilter beanName, position.order
	}

	/**
	 * Register a filter bean name in a specified position in the chain.
	 *
	 * Note - only for use by plugins during bean building - to register at runtime
	 * (preferably in BootStrap) use <code>clientRegisterFilter</code>.
	 *
	 * @param beanName the Spring bean name of the filter
	 * @param order the position (see {@link SecurityFilterPosition})
	 */
	static void registerFilter(String beanName, int order) {
		String oldName = orderedFilters[order]
		assert oldName == null, "Cannot register filter '$beanName' at position $order; '$oldName' is already registered in that position"
		orderedFilters[order] = beanName

		log.trace 'Registered bean "{}" as a filter at order {}', beanName, order
	}

	/**
	 * Register a filter in a specified position in the chain.
	 *
	 * Note - this is for use in application code after the plugin has initialized,
	 * e.g. in BootStrap where you want to register a custom filter in the correct
	 * order without dealing with the existing configured filters.
	 *
	 * @param beanName the Spring bean name of the filter
	 * @param position the position
	 */
	static void clientRegisterFilter(String beanName, SecurityFilterPosition position) {
		clientRegisterFilter beanName, position.order
	}

	/**
	 * Register a filter in a specified position in the chain.
	 *
	 * Note - this is for use in application code after the plugin has initialized,
	 * e.g. in BootStrap where you want to register a custom filter in the correct
	 * order without dealing with the existing configured filters.
	 *
	 * @param beanName the Spring bean name of the filter
	 * @param order the position (see {@link SecurityFilterPosition})
	 */
	@SuppressWarnings('deprecation')
	static void clientRegisterFilter(String beanName, int order) {
		Filter oldFilter = configuredOrderedFilters.get(order)
		assert !oldFilter,
			"Cannot register filter '$beanName' at position $order; '$oldFilter' is already registered in that position"

		Filter filter = getBean(beanName)
		configuredOrderedFilters[order] = filter

		FilterChainProxy filterChain = filterChainProxy
		filterChain.filterChainMap = mergeFilterChainMap(configuredOrderedFilters, filter, order, filterChain.filterChainMap)

		log.trace 'Client registered bean "{}" as a filter at order {}', beanName, order
	}

	private static FilterChainProxy getFilterChainProxy() {
		def bean = getBean('springSecurityFilterChain')
		bean instanceof DebugFilter ? bean.getFilterChainProxy() : (FilterChainProxy)bean
	}

	private static Map<RequestMatcher, List<Filter>> mergeFilterChainMap(Map<Integer, Filter> orderedFilters,
			Filter filter, int order, Map<RequestMatcher, List<Filter>> filterChainMap) {

		Map<Filter, Integer> filterToPosition = new HashMap<Filter, Integer>()
		orderedFilters.each { Integer position, Filter f -> filterToPosition[f] = position }

		Map<RequestMatcher, List<Filter>> fixedFilterChainMap = [:]
		filterChainMap.each { RequestMatcher matcher, List<Filter> value ->
			List<Filter> filters = [] + value // copy
			int index = 0
			while (index < filters.size() && filterToPosition[filters[index]] < order) {
				index++
			}
			filters.add index, filter
			fixedFilterChainMap[matcher] = filters
		}

		fixedFilterChainMap
	}

	/**
	 * Check if the current user is switched to another user.
	 * @return <code>true</code> if logged in and switched
	 */
	static boolean isSwitched() {
		findInferredAuthorities(principalAuthorities).any { authority ->
			(authority instanceof SwitchUserGrantedAuthority) ||
			SwitchUserFilter.ROLE_PREVIOUS_ADMINISTRATOR == ((GrantedAuthority)authority).authority
		}
	}

	/**
	 * Get the username of the original user before switching to another.
	 * @return the original login name
	 */
	static String getSwitchedUserOriginalUsername() {
		if (isSwitched()) {
			((SwitchUserGrantedAuthority)authentication.authorities.find({ it instanceof SwitchUserGrantedAuthority }))?.source?.name
		}
	}

	/**
	 * Lookup the security type as a String to avoid dev mode reload issues.
	 * @return the name of the <code>SecurityConfigType</code>
	 */
	static String getSecurityConfigType() {
		securityConfig.securityConfigType
	}

	/**
	 * Rebuild an Authentication for the given username and register it in the security context.
	 * Typically used after updating a user's authorities or other auth-cached info.
	 *
	 * Also removes the user from the user cache to force a refresh at next login.
	 *
	 * @param username the user's login name
	 * @param password optional
	 */
	static void reauthenticate(String username, String password) {
		UserDetails userDetails = getBean('userDetailsService', UserDetailsService).loadUserByUsername(username)

		SecurityContextHolder.context.authentication = new UsernamePasswordAuthenticationToken(
				userDetails, password == null ? userDetails.password : password, userDetails.authorities)

		getBean('userCache', UserCache).removeUserFromCache username
	}

	/**
	 * Execute a closure with the current authentication. Assumes that there's an authentication in the
	 * http session and that the closure is running in a separate thread from the web request, so the
	 * context and authentication aren't available to the standard ThreadLocal.
	 *
	 * @param closure the code to run
	 * @return the closure's return value
	 */
	static doWithAuth(@SuppressWarnings('rawtypes') Closure closure) {
		boolean set = false
		if (!authentication && SecurityRequestHolder.request) {
			HttpSession httpSession = SecurityRequestHolder.request.getSession(false)
			if (httpSession) {
				def securityContext = httpSession.getAttribute(SPRING_SECURITY_CONTEXT_KEY)
				if (securityContext) {
					SecurityContextHolder.context = (SecurityContext)securityContext
					set = true
				}
			}
		}

		try {
			closure()
		}
		finally {
			if (set) {
				SecurityContextHolder.clearContext()
			}
		}
	}

	/**
	 * Authenticate as the specified user and execute the closure with that authentication. Restores
	 * the authentication to the one that was active if it exists, or clears the context otherwise.
	 *
	 * This is similar to run-as and switch-user but is only local to a Closure.
	 *
	 * @param username the username to authenticate as
	 * @param closure the code to run
	 * @return the closure's return value
	 */
	static doWithAuth(String username, @SuppressWarnings('rawtypes') Closure closure) {
		Authentication previousAuth = authentication
		reauthenticate username, null

		try {
			closure()
		}
		finally {
			if (!previousAuth) {
				SecurityContextHolder.clearContext()
			}
			else {
				SecurityContextHolder.context.authentication = previousAuth
			}
		}
	}

	static SecurityContext getSecurityContext(HttpSession session) {
		def securityContext = session.getAttribute(SPRING_SECURITY_CONTEXT_KEY)
		if (securityContext instanceof SecurityContext) {
			(SecurityContext)securityContext
		}
	}

	/**
	 * Get the last auth exception.
	 * @param session the session
	 * @return the exception
	 */
	static Throwable getLastException(HttpSession session) {
		(Throwable)session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION)
	}

	/**
	 * Get the last attempted username.
	 * @param session the session
	 * @return the username
	 */
	static String getLastUsername(HttpSession session) {
		String username = (String)session.getAttribute(SPRING_SECURITY_LAST_USERNAME_KEY)
		if (username) {
			username = StringEscapeUtils.unescapeHtml(username)
		}
		username
	}

	/**
	 * Get the saved request from the session.
	 * @param session the session
	 * @return the saved request
	 */
	static SavedRequest getSavedRequest(HttpSession session) {
		(SavedRequest)session.getAttribute(SAVED_REQUEST)
	}

	/**
	 * Merge in a secondary config (provided by a plugin as defaults) into the main config.
	 * @param currentConfig the current configuration
	 * @param className the name of the config class to load
	 */
	private static void mergeConfig(ConfigObject currentConfig, String className) {
		ConfigObject secondary = new ConfigSlurper(Environment.current.name).parse(
				  new GroovyClassLoader(this.classLoader).loadClass(className))
		secondary = secondary.security as ConfigObject

		Collection<String> keysToDefaultEmpty = []
		findKeysToDefaultEmpty secondary, '', keysToDefaultEmpty

		def merged = mergeConfig(currentConfig, secondary)

		// having discovered the keys that have map values (since they initially point to empty maps),
		// check them again and remove the damage done when Map values are 'flattened'
		for (String key in keysToDefaultEmpty) {
			Map value = (Map)ReflectionUtils.getConfigProperty(key, merged)
			for (Iterator<Map.Entry> iter = value.entrySet().iterator(); iter.hasNext();) {
				def entry = iter.next()
				if (entry.value instanceof Map) {
					iter.remove()
				}
			}
		}

		_securityConfig = ReflectionUtils.securityConfig = merged
	}

	/**
	 * Given an unmodified config map with defaults, loop through the keys looking for values that are initially
	 * empty maps. This will be used after merging to remove map values that cause problems by being included both as
	 * the result from the ConfigSlurper (which is correct) and as a "flattened" maps which confuse Spring Security.
	 * @param m the config map
	 * @param fullPath the path to this config map, e.g. 'grails.plugin.security
	 * @param keysToDefaultEmpty a collection of key names to add to
	 */
	private static void findKeysToDefaultEmpty(Map m, String fullPath, Collection keysToDefaultEmpty) {
		m.each { k, v ->
			if (v instanceof Map) {
				if (v) {
					// recurse
					findKeysToDefaultEmpty((Map)v, fullPath + '.' + k, keysToDefaultEmpty)
				}
				else {
					// since it's an empty map, capture its path for the cleanup pass
					keysToDefaultEmpty << (fullPath + '.' + k).substring(1)
				}
			}
		}
	}

	/**
	 * Merge two configs together. The order is important if <code>secondary</code> is not null then
	 * start with that and merge the main config on top of that. This lets the <code>secondary</code>
	 * config act as default values but let user-supplied values in the main config override them.
	 *
	 * @param currentConfig the main config, starting from Config.groovy
	 * @param secondary new default values
	 * @return the merged configs
	 */
	private static ConfigObject mergeConfig(ConfigObject currentConfig, ConfigObject secondary) {
		(secondary ?: new ConfigObject()).merge(currentConfig ?: new ConfigObject()) as ConfigObject
	}

	private static Collection<? extends GrantedAuthority> findInferredAuthorities(Collection<GrantedAuthority> granted) {
		getBean('roleHierarchy', RoleHierarchy).getReachableGrantedAuthorities(granted) ?: Collections.emptyList()
	}

	@SuppressWarnings('unchecked')
	private static <T> T getBean(String name, Class<T> c = null) {
		(T)application.mainContext.getBean(name, c)
	}

	/**
	 * Called each time doWithApplicationContext() is invoked, so it's important to reset
	 * to default values when running integration and functional tests together.
	 */
	private static void initializeContext() {
		voterNames.clear()
		voterNames << 'authenticatedVoter' << 'roleVoter' << 'webExpressionVoter' << 'closureVoter'

		logoutHandlerNames.clear()
		logoutHandlerNames << 'rememberMeServices' << 'securityContextLogoutHandler'

		providerNames.clear()
		providerNames << 'daoAuthenticationProvider' << 'anonymousAuthenticationProvider' << 'rememberMeAuthenticationProvider'

		orderedFilters.clear()

		configuredOrderedFilters.clear()

		afterInvocationManagerProviderNames.clear()
	}

	private static Authentication getAuthentication() {
		SecurityContextHolder.context?.authentication
	}
}
