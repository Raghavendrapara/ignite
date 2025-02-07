/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.security;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.GridInternalWrapper;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteNodeAttributes;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.GridCacheSharedContext;
import org.apache.ignite.internal.processors.security.sandbox.IgniteDomainCombiner;
import org.apache.ignite.internal.processors.security.sandbox.IgniteSandbox;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.A;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.marshaller.Marshaller;
import org.apache.ignite.plugin.security.SecurityCredentials;
import org.apache.ignite.plugin.security.SecurityException;
import org.apache.ignite.plugin.security.SecurityPermission;
import org.apache.ignite.plugin.security.SecurityPermissionSet;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.spi.discovery.DiscoverySpiNodeAuthenticator;

/**
 * Security utilities.
 */
public class SecurityUtils {
    /** */
    public static final String MSG_SEC_PROC_CLS_IS_INVALID = "Local node's grid security processor class " +
        "is not equal to remote node's grid security processor class " +
        "[locNodeId=%s, rmtNodeId=%s, locCls=%s, rmtCls=%s]";

    /** Ignite internal package. */
    public static final String IGNITE_INTERNAL_PACKAGE = "org.apache.ignite.internal";

    /** Default serialization version. */
    private static final int DFLT_SERIALIZE_VERSION = isSecurityCompatibilityMode() ? 1 : 2;

    /** Current serialization version. */
    private static final ThreadLocal<Integer> SERIALIZE_VERSION = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() {
            return DFLT_SERIALIZE_VERSION;
        }
    };

    /** Permissions that contain {@code AllPermission}. */
    public static final Permissions ALL_PERMISSIONS;

    /** Code source for ignite-core module. */
    private static final CodeSource CORE_CODE_SOURCE = SecurityUtils.class.getProtectionDomain().getCodeSource();

    /** System types cache. */
    private static final ConcurrentMap<Class<?>, Boolean> SYSTEM_TYPES = new ConcurrentHashMap<>();

    static {
        ALL_PERMISSIONS = new Permissions();

        ALL_PERMISSIONS.add(new AllPermission());
        ALL_PERMISSIONS.setReadOnly();
    }

    /**
     * Private constructor.
     */
    private SecurityUtils() {
    }

    /**
     * @return Security compatibility mode flag.
     */
    public static boolean isSecurityCompatibilityMode() {
        return IgniteSystemProperties.getBoolean(IgniteSystemProperties.IGNITE_SECURITY_COMPATIBILITY_MODE, false);
    }

    /**
     * @param ver Serialize version.
     */
    public static void serializeVersion(int ver) {
        SERIALIZE_VERSION.set(ver);
    }

    /**
     * @return Serialize version.
     */
    public static int serializeVersion() {
        return SERIALIZE_VERSION.get();
    }

    /**
     * Sets default serialize version {@link #DFLT_SERIALIZE_VERSION}.
     */
    public static void restoreDefaultSerializeVersion() {
        serializeVersion(DFLT_SERIALIZE_VERSION);
    }

    /**
     * @return Allow all service permissions.
     */
    public static Map<String, Collection<SecurityPermission>> compatibleServicePermissions() {
        Map<String, Collection<SecurityPermission>> srvcPerms = new HashMap<>();

        srvcPerms.put("*", Arrays.asList(
            SecurityPermission.SERVICE_CANCEL,
            SecurityPermission.SERVICE_DEPLOY,
            SecurityPermission.SERVICE_INVOKE));

        return srvcPerms;
    }

    /**
     * Gets the node's security context.
     *
     * @param marsh Marshaller.
     * @param ldr Class loader.
     * @param node Node.
     * @return Node's security context.
     */
    public static SecurityContext nodeSecurityContext(Marshaller marsh, ClassLoader ldr, ClusterNode node) {
        A.notNull(node, "Cluster node");

        byte[] subjBytes = node.attribute(IgniteNodeAttributes.ATTR_SECURITY_SUBJECT_V2);

        if (subjBytes == null)
            throw new SecurityException("Security context isn't certain.");

        try {
            return U.unmarshal(marsh, subjBytes, ldr);
        }
        catch (IgniteCheckedException e) {
            throw new SecurityException("Failed to get security context.", e);
        }
    }

    /** 
     * @return Current security context if it is different from local node security context, otherwise {@code null}. 
     * @see #withRemoteSecurityContext(GridKernalContext, SecurityContext)
     */
    public static SecurityContext remoteSecurityContext(GridKernalContext ctx) {
        IgniteSecurity security = ctx.security();

        if (!security.enabled() || security.isDefaultContext())
            return null;

        return security.securityContext();
    }

    /** @return Current security subject ID if security is enabled, otherwise null. */
    public static UUID securitySubjectId(GridKernalContext ctx) {
        IgniteSecurity security = ctx.security();

        return security.enabled() ? security.securityContext().subject().id() : null;
    }

    /** @return Current security subject id if security is enabled otherwise null. */
    public static UUID securitySubjectId(GridCacheContext<?, ?> cctx) {
        return securitySubjectId(cctx.kernalContext());
    }

    /** @return Current security subject id if security is enabled otherwise null. */
    public static UUID securitySubjectId(GridCacheSharedContext<?, ?> cctx) {
        return securitySubjectId(cctx.kernalContext());
    }

    /**
     * Sets specified security context as current if it differs from the {@code null}.
     * {@code null} means that security context of the local node is specified or security is disabled so no security
     * context change is needed.
     * Note that this method is safe to use only when it is known to be called in the security context of the local node
     * (e.g. in system workers).
     * @return {@link OperationSecurityContext} instance if new security context is set, otherwise {@code null}.
     */
    public static OperationSecurityContext withRemoteSecurityContext(GridKernalContext ctx, SecurityContext secCtx) {
        if (secCtx == null)
            return null;

        return ctx.security().withContext(secCtx);
    }

    /**
     * Computes a result in a privileged action.
     *
     * @param c Instance of SandboxCallable.
     * @param <T> Type of result.
     * @param <E> Type of Exception.
     * @return Computed result.
     * @throws E if unable to compute a result.
     */
    public static <T, E extends Exception> T doPrivileged(Callable<T> c) throws E {
        try {
            return AccessController.doPrivileged((PrivilegedExceptionAction<T>)c::call);
        }
        catch (PrivilegedActionException e) {
            throw (E)e.getException();
        }
    }

    /**
     * @return True if SecurityManager is installed.
     */
    public static boolean hasSecurityManager() {
        return System.getSecurityManager() != null;
    }

    /** @return True if class of {@code target} is a system type. */
    public static boolean isSystemType(GridKernalContext ctx, Object target, boolean considerWrapperCls) {
        if (considerWrapperCls)
            target = unwrap(target);

        return isSystemType(ctx, target.getClass());
    }

    /** @return Whether specified class is a system type. */
    public static boolean isSystemType(GridKernalContext ctx, Class<?> cls) {
        Boolean isSysType = SYSTEM_TYPES.get(cls);

        if (isSysType == null) {
            ProtectionDomain pd = doPrivileged(cls::getProtectionDomain);

            SYSTEM_TYPES.put(cls, isSysType = (pd == null) || F.eq(CORE_CODE_SOURCE, pd.getCodeSource()));
        }

        return isSysType;
    }

    /** */
    public static Object unwrap(Object target) {
        return target instanceof GridInternalWrapper ? ((GridInternalWrapper<?>)target).userObject() : target;
    }

    /**
     * @return True if current thread runs inside the Ignite Sandbox.
     */
    public static boolean isInsideSandbox() {
        if (!IgniteSecurityProcessor.hasSandboxedNodes())
            return false;

        final AccessControlContext ctx = AccessController.getContext();

        return AccessController.doPrivileged((PrivilegedAction<Boolean>)
            () -> ctx.getDomainCombiner() instanceof IgniteDomainCombiner
        );
    }

    /**
     * @return Proxy of {@code instance} if the sandbox is enabled and class of {@code instance} is not a system type
     * otherwise {@code instance}.
     */
    public static <T> T sandboxedProxy(GridKernalContext ctx, final Class cls, final T instance) {
        if (instance == null)
            return null;

        Objects.requireNonNull(ctx, "Parameter 'ctx' cannot be null.");
        Objects.requireNonNull(cls, "Parameter 'cls' cannot be null.");

        final IgniteSandbox sandbox = ctx.security().sandbox();

        if (sandbox.enabled() && !isSystemType(ctx, instance, true)) {
            return (T)Proxy.newProxyInstance(sandbox.getClass().getClassLoader(),
                proxyClasses(cls, instance), new SandboxInvocationHandler(sandbox, instance));
        }

        return instance;
    }

    /** Array of proxy classes. */
    private static <T> Class[] proxyClasses(Class cls, T instance) {
        return instance instanceof GridInternalWrapper
            ? new Class[] {cls, GridInternalWrapper.class}
            : new Class[] {cls};
    }

    /** */
    private static class SandboxInvocationHandler<T> implements InvocationHandler {
        /** */
        private final IgniteSandbox sandbox;

        /** */
        private final Object original;

        /** */
        public SandboxInvocationHandler(IgniteSandbox sandbox, Object original) {
            this.sandbox = sandbox;
            this.original = original;
        }

        /** {@inheritDoc} */
        @Override public Object invoke(Object proxy, Method mtd, Object[] args) throws Throwable {
            try {
                if (proxy instanceof GridInternalWrapper &&
                    GridInternalWrapper.class.getMethod(mtd.getName(), mtd.getParameterTypes()) != null)
                    return mtd.invoke(original, args);
            }
            catch (NoSuchMethodException ignore) {
                // Ignore.
            }

            return sandbox.execute(() -> {
                try {
                    return (T)mtd.invoke(original, args);
                }
                catch (InvocationTargetException e) {
                    throw new IgniteException(e.getTargetException());
                }
            });
        }
    }

    /**
     * Marshals specified security context and adds it to the node attributes.
     *
     * @param secCtx Security context to be added.
     * @param nodeAttrs Cluster node attributes to which security context attribute is to be added.
     * @param marsh Marshaller.
     * @return New copy of node attributes with security context attribute added.
     * @throws IgniteCheckedException If security context serialization exception occurs.
     */
    public static Map<String, Object> withSecurityContext(
        SecurityContext secCtx,
        Map<String, Object> nodeAttrs,
        Marshaller marsh
    ) throws IgniteCheckedException {
        if (!(secCtx instanceof Serializable))
            throw new IgniteSpiException("Authentication subject is not serializable.");

        Map<String, Object> res = new HashMap<>(nodeAttrs);

        res.put(IgniteNodeAttributes.ATTR_SECURITY_SUBJECT_V2, U.marshal(marsh, secCtx));

        return res;
    }

    /**
     * Performs local node authentication.
     *
     * @param node Cluster node to authenticate.
     * @param cred Node credentials.
     * @param nodeAuth Node authenticator.
     * @return {@link SecurityContext} instance as authentication result.
     */
    public static SecurityContext authenticateLocalNode(
        ClusterNode node,
        SecurityCredentials cred,
        DiscoverySpiNodeAuthenticator nodeAuth
    ) {
        assert nodeAuth != null;
        assert cred != null || node.attribute(IgniteNodeAttributes.ATTR_AUTHENTICATION_ENABLED) != null;

        SecurityContext secCtx = nodeAuth.authenticateNode(node, cred);

        if (secCtx == null)
            throw new IgniteSpiException("Authentication failed for local node: " + node.id());

        return secCtx;
    }

    /** */
    public static void authorizeAll(IgniteSecurity security, SecurityPermissionSet permissions) {
        if (!security.enabled())
            return;

        if (permissions.systemPermissions() != null) {
            for (SecurityPermission permission : permissions.systemPermissions())
                security.authorize(permission);
        }

        authorizeAll(security, permissions.cachePermissions());
        authorizeAll(security, permissions.taskPermissions());
        authorizeAll(security, permissions.servicePermissions());
    }

    /** */
    private static void authorizeAll(IgniteSecurity security, Map<String, Collection<SecurityPermission>> permissions) {
        if (F.isEmpty(permissions))
            return;

        permissions.forEach((name, permsPerName) -> permsPerName.forEach(perm -> security.authorize(name, perm)));
    }
}
