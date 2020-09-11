/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.adapter.dubbo;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.ResourceTypeConstants;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.adapter.dubbo.config.DubboConfig;
import com.alibaba.csp.sentinel.adapter.dubbo.fallback.DubboFallbackRegistry;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.InvokeMode;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.RpcUtils;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;

/**
 * <p>Sentinel下的Dubbo service consumer filter. 默认情况下自动激活。</p>
 * <p>
 * 如果要禁用consumer filter，可以配置：dubbo:consumer filter="-sentinel.dubbo.consumer.filter"
 * <pre>
 * </pre>
 *
 * @author Carpenter Lee
 * @author Eric Zhao
 */
@Activate(group = CONSUMER) // 通过 @Activate 注解定义该 Filter 在客户端生效
public class SentinelDubboConsumerFilter extends BaseSentinelDubboFilter {

    public SentinelDubboConsumerFilter() {
        RecordLog.info("Sentinel Apache Dubbo consumer filter initialized");
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        Entry interfaceEntry = null;
        Entry methodEntry = null;
        RpcContext rpcContext = RpcContext.getContext();
        try {
            /**
             * 在 Sentinel 中一个非常核心的概念就是资源，即要定义限流的目标，当出现什么异常（匹配用户配置的规则）对什么进行熔断操作，Dubbo 服务中的资源通常是 Dubbo 服务，分为服务接口级或方法级，故该方法返回 Dubbo 的资源名，其主要实现特征如下：
             * - 如果启用用户定义资源的前缀，默认为 false ，可以通过配置属性：csp.sentinel.dubbo.resource.use.prefix 来定义是否需要启用前缀。如果启用前缀，消费端的默认前缀为 dubbo:consumer:，可以通过配置属性 csp.sentinel.dubbo.resource.consumer.prefix 来自定义消费端的资源前缀
             * - Dubbo 资源的名称表示方法为：interfaceName + ":" + methodName + "(" + "paramTyp1参数列表，多个用 , 隔开" + ")"
             */
            String methodResourceName = DubboUtils.getResourceName(invoker, invocation, DubboConfig.getDubboConsumerPrefix());
            /**
             * 调用 Sentinel 核心API  SphU.entry 进入 Dubbo InterfaceName。从方法的名称我们也能很容易的理解，就是使用 Sentienl API 进入资源名为 Dubbo 接口提供者类全路径限定名，即认为调用该方法，Sentienl 会收集该资源的调用信息，然后Sentinel 根据运行时收集的信息，再配合限流规则，熔断等规则进行计算是否需要限流或熔断。本节我们不打算深入研究 SphU 的核心方法研究，先初步了解该方法：
             * - String name 资源的名称
             * - int resourceType 资源的类型，在 Sentinel 中目前定义了 如下五中资源：
             *   - ResourceTypeConstants.COMMON：同样类型
             *   - ResourceTypeConstants.COMMON_WEB：WEB 类资源
             *   - ResourceTypeConstants.COMMON_RPC：RPC 类型
             *   - ResourceTypeConstants.COMMON_API_GATEWAY：接口网关
             *   - ResourceTypeConstants.COMMON_DB_SQL：数据库 SQL 语句
             * - EntryType type：进入资源的方式，主要分为 EntryType.OUT、EntryType.IN，只有 EntryType.IN 方式才能对资源进行阻塞
             */
            String interfaceResourceName = DubboConfig.getDubboInterfaceGroupAndVersionEnabled() ? invoker.getUrl().getColonSeparatedKey()
                    : invoker.getInterface().getName();
            InvokeMode invokeMode = RpcUtils.getInvokeMode(invoker.getUrl(), invocation);

            if (InvokeMode.SYNC == invokeMode) {
                interfaceEntry = SphU.entry(interfaceResourceName, ResourceTypeConstants.COMMON_RPC, EntryType.OUT);
                rpcContext.set(DubboUtils.DUBBO_INTERFACE_ENTRY_KEY, interfaceEntry);
                // 调用 Sentinel 核心API SphU.entry 进入 Dubbo method 级别
                methodEntry = SphU.entry(methodResourceName, ResourceTypeConstants.COMMON_RPC, EntryType.OUT, invocation.getArguments());
            } else {
                // should generate the AsyncEntry when the invoke model in future or async
                interfaceEntry = SphU.asyncEntry(interfaceResourceName, ResourceTypeConstants.COMMON_RPC, EntryType.OUT);
                rpcContext.set(DubboUtils.DUBBO_INTERFACE_ENTRY_KEY, interfaceEntry);
                methodEntry = SphU.asyncEntry(methodResourceName, ResourceTypeConstants.COMMON_RPC, EntryType.OUT, 1, invocation.getArguments());
            }
            rpcContext.set(DubboUtils.DUBBO_METHOD_ENTRY_KEY, methodEntry);
            // 调用 Dubbo 服务提供者方法
            return invoker.invoke(invocation);
        } catch (BlockException e) {
            // 如果出现调用异常，可以通过 Sentinel 的 Tracer.traceEntry 跟踪本次调用资源进入的情况，详细 API 将在该系列的后续文章中详细介绍
            // 如果是由于触发了限流、熔断等操作，抛出了阻塞异常，可通过 注册 ConsumerFallback 来实现消费者快速失败，将在下文详细介绍
            return DubboFallbackRegistry.getConsumerFallback().handle(invoker, invocation, e);
        }
    }
}


