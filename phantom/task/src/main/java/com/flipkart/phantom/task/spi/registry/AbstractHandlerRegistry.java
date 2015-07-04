/*
 * Copyright 2012-2015, the original author or authors.
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

package com.flipkart.phantom.task.spi.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.trpr.platform.core.PlatformException;
import org.trpr.platform.core.impl.logging.LogFactory;
import org.trpr.platform.core.spi.logging.Logger;

import com.flipkart.phantom.task.impl.TaskHandler;
import com.flipkart.phantom.task.spi.AbstractHandler;
import com.flipkart.phantom.task.spi.TaskContext;
import com.github.kristofa.brave.TraceFilter;

/**
 * Interface for handler registry. Controls lifecycle methods of all handlers understood by the registry.
 *
 * @author kartikbu
 * @version 1.0
 * @created 30/7/13 12:43 AM
 */
public abstract class AbstractHandlerRegistry<T extends AbstractHandler> {

	/** Logger for this class*/
	private static final Logger LOGGER = LogFactory.getLogger(AbstractHandlerRegistry.class);
	
	/** Default value for handler init concurrency */
	private static final int DEFAULT_HANDLER_INIT_CONCURRENCY = 5;
	
	/** The handler init concurrency */
	private int handlerInitConcurrency = AbstractHandlerRegistry.DEFAULT_HANDLER_INIT_CONCURRENCY;
	
    /** Map of AbstractHandlerS keyed by their names */
    protected Map<String,T> handlers = new HashMap<String,T>();

    /** Map of TraceFilterS keyed by the Handler names */
    protected Map<String,TraceFilter> traceFilters = new HashMap<String,TraceFilter>();
    
    /**
     * Lifecycle init method. Initializes all individual handlers understood.
     * @param handlerConfigInfoList List of HandlerConfigInfo which is to be analyzed and initialized
     * @param taskContext The task context object
     * @return array of AbstractHandlerRegistry.InitedHandlerInfo instances for each inited handler
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
	public AbstractHandlerRegistry.InitedHandlerInfo<T>[] init(List<HandlerConfigInfo> handlerConfigInfoList, TaskContext taskContext) throws Exception {
    	// this is a synchronized list as multiple threads add to it and we also iterate through it
    	List<AbstractHandlerRegistry.InitedHandlerInfo<T>> initedHandlerInfos = Collections.synchronizedList(new LinkedList<AbstractHandlerRegistry.InitedHandlerInfo<T>>());
    	// we want to init handlers defined as HandlerConfigInfo#FIRST_ORDER first, serially, before loading others in parallel
    	// create a new list as we are changing ordering of elements being passed in
    	List<HandlerConfigInfo> tempHandlerConfigInfoList = new LinkedList<HandlerConfigInfo>();
    	tempHandlerConfigInfoList.addAll(handlerConfigInfoList);
    	Collections.sort(tempHandlerConfigInfoList, new Comparator<HandlerConfigInfo>() {
			public int compare(HandlerConfigInfo o1, HandlerConfigInfo o2) { 
				// sort by ascending order of HandlerConfigInfo#getLoadOrder()
				return (o1.getLoadOrder() - o2.getLoadOrder());
			}
    	});
        for (int i=0; i<tempHandlerConfigInfoList.size(); i++) {
        	HandlerConfigInfo handlerConfigInfo = tempHandlerConfigInfoList.get(i);
        	if (handlerConfigInfo.getLoadOrder() == HandlerConfigInfo.FIRST_ORDER) {
        		this.initHandlers(initedHandlerInfos, taskContext, 1, handlerConfigInfo); // we load this serially
        	} else {
        		this.initHandlers(initedHandlerInfos, taskContext, this.getHandlerInitConcurrency(), 
        				tempHandlerConfigInfoList.subList(i, tempHandlerConfigInfoList.size()).toArray(new HandlerConfigInfo[0])); // load all remaining handlers in parallel
        		break;
        	}
        }
        return initedHandlerInfos.toArray(new AbstractHandlerRegistry.InitedHandlerInfo[0]);        
    }

    /**
     * Method to reinitialize a handler.
     * @param name Name of the handler.
     * @param taskContext task context object
     * @throws Exception
     */
    public void reinitHandler(String name, TaskContext taskContext) throws Exception {
        T handler = this.handlers.get(name);
        if (handler != null) {
            try {
                handler.deactivate();
                handler.shutdown(taskContext);
                handler.init(taskContext);
                handler.activate();
            } catch (Exception e) {
                LOGGER.error("Error initializing " + this.getHandlerType().getName() + " : {}. Error is: " + e.getMessage(), handler.getName(), e);
                throw new PlatformException("Error reinitialising "  + this.getHandlerType().getName() + " : " + handler.getName(), e);
            }
        }    	
    }

    /**
     * Lifecycle shutdown method. Shuts down all individual handlers understood.
     * @param taskContext The task context object
     * @throws Exception
     */
    public void shutdown(TaskContext taskContext) throws Exception {
        for (String name : this.handlers.keySet()) {
            LOGGER.info("Shutting down {}: " + name, this.getHandlerType().getName());
            try {
            	this.handlers.get(name).shutdown(taskContext);
            	this.handlers.get(name).deactivate();
            	this.unregisterTaskHandler(this.handlers.get(name));
            } catch (Exception e) {
                LOGGER.warn("Failed to shutdown {}: " + name, this.getHandlerType().getName(), e);
            }
        }    	
    }

    /**
     * Enumeration method for all handlers. Returns a List of AbstractHandler instances
     * @return List
     */
    public List<T> getHandlers() {
        return new ArrayList<T>(this.handlers.values());
    }

    /**
     * Get a handler given name
     * @param name String name of the handler
     * @return AbstractHandler
     */
    public T getHandler(String name) {
        return this.handlers.get(name);
    }
    
    /**
     * Gets the TraceFilter associated with the handler identified by the specified name 
     * @param handlerName the TaskHandler name
     * @return TraceFilter instance for the TaskHandler
     */
    public TraceFilter getTraceFilterForHandler(String handlerName) {
    	return this.traceFilters.get(handlerName);
    }
    
	/**
	 * Unregisters (removes) a AbstractHandler from this registry.
	 * @param handler the AbstractHandler to be removed
	 */
    public void unregisterTaskHandler(T handler) {
    	this.handlers.remove(handler.getName());
    	this.traceFilters.remove(handler.getName());
    	this.postUnregisterHandler(handler);
    };
    
    /**
     * Returns the {@link AbstractHandler} type that this registry manages 
     * @return the AbstractHandler type
     */
    protected abstract Class<T> getHandlerType();
    
    /**
     * Callback method after initing handler. Subtypes may override to perform custom post init operations
     * @param handler the AbstractHandler that was inited
     */
    protected void postInitHandler(T handler) {
    	// no op
    }
    
    /**
     * Callback method after unregistering handler. Subtypes may override to perform custom post unregister operations
     * @param handler the AbstractHandler that was unregistered
     */
    protected void postUnregisterHandler(T handler) {
    	// no op    	
    }
    
    /**
     *  Container object for inited handlers and the respective configuration
     */
    public static final class InitedHandlerInfo<T extends AbstractHandler> {
    	private T initedHandler;
    	private HandlerConfigInfo handlerConfigInfo;
    	public InitedHandlerInfo(T initedHandler, HandlerConfigInfo handlerConfigInfo) {
    		this.initedHandler = initedHandler;
    		this.handlerConfigInfo = handlerConfigInfo;
    	}
		public T getInitedHandler() {
			return initedHandler;
		}
		public HandlerConfigInfo getHandlerConfigInfo() {
			return handlerConfigInfo;
		}
    }
    
    /**
     * Helper method to init handlers with the specified concurrency
     * @param initedHandlerInfos the list to add meta data of all successfully inited handlers
     * @param taskContext TaskContext instance to pass to all handlers during init
     * @param concurrency the concurrent number of handler inits
     * @param configInfos HandlerConfigInfo instances containing handlers to be inited
     */
    @SuppressWarnings("unchecked")
	private void initHandlers(List<AbstractHandlerRegistry.InitedHandlerInfo<T>> initedHandlerInfos, TaskContext taskContext,
    		int concurrency, HandlerConfigInfo...configInfos) {
    	ExecutorService pool = Executors.newFixedThreadPool(concurrency,new ThreadFactory() {
    		int nameSuffix = -1;
			public Thread newThread(Runnable r) {
				nameSuffix += 1;
				return new Thread(r,"Handler-Init-Thread-" + nameSuffix);
			}
    	});
    	List<FutureTask<T>> handlerInitTasks = new LinkedList<FutureTask<T>>();
    	for (HandlerConfigInfo handlerConfigInfo : configInfos) {
	        String[] handlerBeanIds = handlerConfigInfo.getProxyHandlerContext().getBeanNamesForType(this.getHandlerType());
	        for (String taskHandlerBeanId : handlerBeanIds) {
	        	T handler = (T) handlerConfigInfo.getProxyHandlerContext().getBean(taskHandlerBeanId);
                handler.setVersion(handlerConfigInfo.getVersion());
	        	FutureTask<T> handlerInitTask = new FutureTask<T>(new HandlerInitFutureTask(handler,taskContext,handlerConfigInfo,initedHandlerInfos));
	        	handlerInitTasks.add(handlerInitTask);
            	pool.execute(handlerInitTask);
	        }
    	}
    	for (FutureTask<T> handlerInitTask : handlerInitTasks) {
    		T initedHandler = null;
    		try {
				initedHandler = handlerInitTask.get();				
			} catch (Exception e) {
                LOGGER.error("Error initializing handlers of type : " + getHandlerType().getName() + ".Error is: " + e.getMessage(), e);
                pool.shutdownNow();
	            throw new PlatformException("Error initializing handlers of type : " + getHandlerType().getName() + ".Error is: " + e.getMessage(), e);
			}
            if (!initedHandler.isActive()) {
            	if (initedHandler.getInitOutcomeStatus() == AbstractHandler.VETO_INIT) {        	            	
                    pool.shutdownNow();
                    throw new PlatformException("Error initializing vetoing handler " + getHandlerType().getName() + " : " + initedHandler.getName());
            	} else {
            		LOGGER.warn("Continuing after init failed for non-vetoing handler " + getHandlerType().getName() + " : " + initedHandler.getName());
            	}
            }	
    	}
		LOGGER.info("Number of handlers inited : " + handlerInitTasks.size());
    	while(!pool.isTerminated()) {
    		pool.shutdown();
    		try {
				pool.awaitTermination(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				// we dont expect this to happen, but if it does, throw a Platform exception
                throw new PlatformException("Error initializing handlers. Init interrupted. Error is : " + e.getMessage(), e);
			}
    	}
    }
    
    /**
     * {@link Callable} implementation for initing {@link TaskHandler} instances asynchronously using {@link Future}
     */
    private class HandlerInitFutureTask implements Callable<T> {
    	T handler;
    	TaskContext taskContext;
    	HandlerConfigInfo handlerConfigInfo;
    	List<AbstractHandlerRegistry.InitedHandlerInfo<T>> initedHandlerInfos;
    	HandlerInitFutureTask(T handler,TaskContext taskContext,HandlerConfigInfo handlerConfigInfo,List<AbstractHandlerRegistry.InitedHandlerInfo<T>> initedHandlerInfos) {
    		this.handler = handler;
    		this.taskContext = taskContext;
    		this.handlerConfigInfo = handlerConfigInfo;
    		this.initedHandlerInfos = initedHandlerInfos;
    	}
		public T call() throws Exception {
            try {
            	if (!handler.isActive()) { // init the handler only if not inited already
	                LOGGER.info("Initializing {} : " + handler.getName(), getHandlerType().getName());
	                handler.init(taskContext);
		            // call post init for any registry specific handling
		            postInitHandler(handler);
	                handler.activate();
	                initedHandlerInfos.add(new AbstractHandlerRegistry.InitedHandlerInfo<T>(handler,handlerConfigInfo));                    	            			
		            // put in all handlers map
		            handlers.put(handler.getName(),handler);
		            // add the trace filter for the handler
		            traceFilters.put(handler.getName(), handler.getTraceFilter());
            	}
            } catch (Exception e) {
                LOGGER.error("Error initializing " + getHandlerType().getName() + " : {}. Error is: " + e.getMessage(), handler.getName(), e);
                // consuming the exception here. Failures will be handled in Future#get()
            }
			return handler;
		}
    }

    /** Getter/Setter methods */
	public int getHandlerInitConcurrency() {
		return handlerInitConcurrency;
	}
	public void setHandlerInitConcurrency(int handlerInitConcurrency) {
		this.handlerInitConcurrency = handlerInitConcurrency;
	}

}
