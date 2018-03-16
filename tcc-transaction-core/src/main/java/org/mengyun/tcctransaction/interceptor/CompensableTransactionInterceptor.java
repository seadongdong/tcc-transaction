package org.mengyun.tcctransaction.interceptor;

import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.mengyun.tcctransaction.NoExistedTransactionException;
import org.mengyun.tcctransaction.SystemException;
import org.mengyun.tcctransaction.Transaction;
import org.mengyun.tcctransaction.TransactionManager;
import org.mengyun.tcctransaction.api.Compensable;
import org.mengyun.tcctransaction.api.Propagation;
import org.mengyun.tcctransaction.api.TransactionContext;
import org.mengyun.tcctransaction.api.TransactionStatus;
import org.mengyun.tcctransaction.common.MethodType;
import org.mengyun.tcctransaction.support.FactoryBuilder;
import org.mengyun.tcctransaction.utils.CompensableMethodUtils;
import org.mengyun.tcctransaction.utils.ReflectionUtils;
import org.mengyun.tcctransaction.utils.TransactionUtils;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Created by changmingxie on 10/30/15.
 */
public class CompensableTransactionInterceptor {

    static final Logger logger = Logger.getLogger(CompensableTransactionInterceptor.class.getSimpleName());

    private TransactionManager transactionManager;

    private Set<Class<? extends Exception>> delayCancelExceptions;

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public void setDelayCancelExceptions(Set<Class<? extends Exception>> delayCancelExceptions) {
        this.delayCancelExceptions = delayCancelExceptions;
    }

    public Object interceptCompensableMethod(ProceedingJoinPoint pjp) throws Throwable {

        Method method = CompensableMethodUtils.getCompensableMethod(pjp);

        Compensable compensable = method.getAnnotation(Compensable.class);
        Propagation propagation = compensable.propagation();
        TransactionContext transactionContext = FactoryBuilder.factoryOf(compensable.transactionContextEditor()).getInstance().get(pjp.getTarget(), method, pjp.getArgs());

        boolean asyncConfirm = compensable.asyncConfirm();

        boolean asyncCancel = compensable.asyncCancel();

        boolean isTransactionActive = transactionManager.isTransactionActive();

        if (!TransactionUtils.isLegalTransactionContext(isTransactionActive, propagation, transactionContext)) {
            throw new SystemException("no active compensable transaction while propagation is mandatory for method " + method.getName());
        }

        MethodType methodType = CompensableMethodUtils.calculateMethodType(propagation, isTransactionActive, transactionContext);

        switch (methodType) {
            case ROOT:
            		logger.info("开始root事务");
                return rootMethodProceed(pjp, asyncConfirm, asyncCancel);
            case PROVIDER:
            		logger.info("开始PROVIDER事务");
                return providerMethodProceed(pjp, transactionContext, asyncConfirm, asyncCancel);
            default:
            		logger.info("开始default事务");
                return pjp.proceed();
        }
    }


    private Object rootMethodProceed(ProceedingJoinPoint pjp, boolean asyncConfirm, boolean asyncCancel) throws Throwable {

    		logger.info("开始root事务");
    	
        Object returnValue = null;

        Transaction transaction = null;

        try {

            transaction = transactionManager.begin();

            try {
            	    logger.info("root 事务 开始切面事务");
                returnValue = pjp.proceed();
                logger.info("root 事务 结束切面事务");
            } catch (Throwable tryingException) {

            		logger.error("root 事务 切面发生异常",tryingException);
                if (isDelayCancelException(tryingException)) {
                    transactionManager.syncTransaction();
                } else {
                    logger.warn(String.format("compensable transaction trying failed. transaction content:%s", JSON.toJSONString(transaction)), tryingException);
                    logger.info("事务开始回滚",tryingException);
                    transactionManager.rollback(asyncCancel);
                }

                throw tryingException;
            }

            transactionManager.commit(asyncConfirm);

        } finally {
        		logger.info("事务结束从线程队列事务移除");
            transactionManager.cleanAfterCompletion(transaction);
        }

        return returnValue;
    }

    private Object providerMethodProceed(ProceedingJoinPoint pjp, TransactionContext transactionContext, boolean asyncConfirm, boolean asyncCancel) throws Throwable {

    		logger.info("开始分支事务");
    		
        Transaction transaction = null;
        try {

            switch (TransactionStatus.valueOf(transactionContext.getStatus())) {
                case TRYING:
                		logger.info("分支事务 TYR--------");
                    transaction = transactionManager.propagationNewBegin(transactionContext);
                    return pjp.proceed();
                case CONFIRMING:
                		logger.info("分支事务 CONFIRMING--------");
                    try {
                        transaction = transactionManager.propagationExistBegin(transactionContext);
                        transactionManager.commit(asyncConfirm);
                    } catch (NoExistedTransactionException excepton) {
                        //the transaction has been commit,ignore it.
                    }
                    break;
                case CANCELLING:
                		logger.info("分支事务 CANCELLING--------");
                    try {
                        transaction = transactionManager.propagationExistBegin(transactionContext);
                        transactionManager.rollback(asyncCancel);
                    } catch (NoExistedTransactionException exception) {
                        //the transaction has been rollback,ignore it.
                    }
                    break;
            }

        } finally {
            transactionManager.cleanAfterCompletion(transaction);
        }

        Method method = ((MethodSignature) (pjp.getSignature())).getMethod();

        return ReflectionUtils.getNullValue(method.getReturnType());
    }

    private boolean isDelayCancelException(Throwable throwable) {

        if (delayCancelExceptions != null) {
            for (Class delayCancelException : delayCancelExceptions) {

                Throwable rootCause = ExceptionUtils.getRootCause(throwable);

                if (delayCancelException.isAssignableFrom(throwable.getClass())
                        || (rootCause != null && delayCancelException.isAssignableFrom(rootCause.getClass()))) {
                    return true;
                }
            }
        }

        return false;
    }

}
