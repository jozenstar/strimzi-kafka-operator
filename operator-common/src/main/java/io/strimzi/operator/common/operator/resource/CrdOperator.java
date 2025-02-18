/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.operator.common.Util;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

@SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
        justification = "Erroneous on Java 11: https://github.com/spotbugs/spotbugs/issues/756")
public class CrdOperator<C extends KubernetesClient,
            T extends CustomResource,
            L extends CustomResourceList<T>>
        extends AbstractWatchableStatusedResourceOperator<C, T, L, Resource<T>> {

    private final Class<T> cls;
    private final Class<L> listCls;

    /**
     * Constructor
     * @param vertx The Vertx instance
     * @param client The Kubernetes client
     * @param cls The class of the CR
     * @param listCls The class of the list.
     * @param crd The CustomResourceDefinition of the CR
     */
    public CrdOperator(Vertx vertx, C client, Class<T> cls, Class<L> listCls, CustomResourceDefinition crd) {
        super(vertx, client, crd.getSpec().getNames().getKind());
        this.cls = cls;
        this.listCls = listCls;
    }

    @Override
    protected MixedOperation<T, L, Resource<T>> operation() {
        return client.customResources(cls, listCls);
    }

    /**
     * The selfClosingWatch does not work for Custom Resources. Therefore we override the method and delete custom
     * resources without it.
     *
     * @param namespace Namespace of the resource which should be deleted
     * @param name Name of the resource which should be deleted
     * @param cascading Defines whether the delete should be cascading or not (e.g. whether a STS deletion should delete pods etc.)
     *
     * @return A future which will be completed on the context thread
     *         once the resource has been deleted.
     */
    @Override
    protected Future<ReconcileResult<T>> internalDelete(String namespace, String name, boolean cascading) {
        Resource<T> resourceOp = operation().inNamespace(namespace).withName(name);

        Future<Void> watchForDeleteFuture = Util.waitFor(vertx,
            String.format("%s resource %s", resourceKind, name),
            "deleted",
            1_000,
            deleteTimeoutMs(),
            () -> resourceOp.get() != null);

        Future<Void> deleteFuture = resourceSupport.deleteAsync(resourceOp.withPropagationPolicy(cascading ? DeletionPropagation.FOREGROUND : DeletionPropagation.ORPHAN).withGracePeriod(-1L));

        return CompositeFuture.join(watchForDeleteFuture, deleteFuture).map(ReconcileResult.deleted());
    }

    public Future<T> patchAsync(T resource) {
        return patchAsync(resource, true);
    }

    public Future<T> patchAsync(T resource, boolean cascading) {
        Promise<T> blockingPromise = Promise.promise();

        vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(future -> {
            String namespace = resource.getMetadata().getNamespace();
            String name = resource.getMetadata().getName();
            try {
                T result = operation().inNamespace(namespace).withName(name).withPropagationPolicy(cascading ? DeletionPropagation.FOREGROUND : DeletionPropagation.ORPHAN).patch(resource);
                log.debug("{} {} in namespace {} has been patched", resourceKind, name, namespace);
                future.complete(result);
            } catch (Exception e) {
                log.debug("Caught exception while patching {} {} in namespace {}", resourceKind, name, namespace, e);
                future.fail(e);
            }
        }, true, blockingPromise);

        return blockingPromise.future();
    }

    public Future<T> updateStatusAsync(T resource) {
        Promise<T> blockingPromise = Promise.promise();

        vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(future -> {
            String namespace = resource.getMetadata().getNamespace();
            String name = resource.getMetadata().getName();

            try {
                T result = operation().inNamespace(namespace).withName(name).updateStatus(resource);
                log.info("Status of {} {} in namespace {} has been updated", resourceKind, name, namespace);
                future.complete(result);
            } catch (Exception e) {
                log.debug("Caught exception while updating status of {} {} in namespace {}", resourceKind, name, namespace, e);
                future.fail(e);
            }
        }, true, blockingPromise);

        return blockingPromise.future();
    }
}
