package com.Albert.searchImpl.boxSearchImpl;

import com.Albert.cache.EfficientCacheCompute;
import com.Albert.pojo.MessageOfSearch;
import com.Albert.pojo.RuleParameter;
import com.Albert.search.boxSearch.CacheEntirelySearch;
import com.Albert.searchModel.SearchModel;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * @author Albert
 * @create 2018-02-03 21:12
 */
public class ConcurrentCacheEntirelySearch<KeySearchT, ResultT, CanBeSearchedT> implements CacheEntirelySearch<KeySearchT, ResultT> {

    private static final long MAX_WAIT_MILLISECOND = 1000 * 60 * 2;
    private static final int NOT_LIMIT_EXPECT_NUM = 0;
    public static final int NOT_HAVE_TIMEOUT = 0;

    private final SearchModel<KeySearchT, ResultT, CanBeSearchedT> searchModel;
    private final EfficientCacheCompute<KeySearchT, SoftReference<BlockingQueue<ResultT>>> cacheResults;
    private final ExecutorService searchService;
    private final ExecutorService gitService;
    private final List<CanBeSearchedT> rootCanBeSearched;

    public ConcurrentCacheEntirelySearch(SearchModel searchModel, List<CanBeSearchedT> rootCanBeSearched) {
        this.searchModel = searchModel;
        this.cacheResults = EfficientCacheCompute.createNeedComputeFunction(this::methodOfHowSearch);
        this.searchService = Executors.newCachedThreadPool();
        this.gitService = Executors.newCachedThreadPool();
        this.rootCanBeSearched = rootCanBeSearched;
    }

    private ConcurrentCacheEntirelySearch(SearchModel searchModel, List<CanBeSearchedT> rootCanBeSearched, ExecutorService searchService) {
        this.searchModel = searchModel;
        this.searchService = searchService;
        this.rootCanBeSearched = rootCanBeSearched;
        this.gitService = Executors.newCachedThreadPool();
        this.cacheResults = EfficientCacheCompute.createNeedComputeFunction(this::methodOfHowSearch);
    }

    public ConcurrentCacheEntirelySearch(SearchModel searchModel, List<CanBeSearchedT> rootCanBeSearched, ExecutorService searchService, ExecutorService gitService) {
        this.searchModel = searchModel;
        this.searchService = searchService;
        this.gitService = gitService;
        this.rootCanBeSearched = rootCanBeSearched;
        this.cacheResults = EfficientCacheCompute.createNeedComputeFunction(this::methodOfHowSearch);
    }

    public static <CanBeSearchedT> ConcurrentCacheEntirelySearch createHowAppointSearchExecutor(SearchModel searchModel, List<CanBeSearchedT> rootCanBeSearched, ExecutorService searchService) {
        return new ConcurrentCacheEntirelySearch(searchModel, rootCanBeSearched, searchService);
    }

    public static <CanBeSearchedT> ConcurrentCacheEntirelySearch createHowAppointSearchExecutorAndGitExecutor(SearchModel searchModel, List<CanBeSearchedT> rootCanBeSearched, ExecutorService searchService, ExecutorService gitService) {
        return new ConcurrentCacheEntirelySearch(searchModel, rootCanBeSearched, searchService, gitService);
    }

    private SoftReference<BlockingQueue<ResultT>> methodOfHowSearch(KeySearchT keySearch) {
        KeyAndResults keyAndResults = initParameter(keySearch);
        startAllSearch(keyAndResults, rootCanBeSearched);
        return new SoftReference<>(keyAndResults.results);
    }

    private KeyAndResults initParameter(KeySearchT keySearch) {
        BlockingQueue<ResultT> results = new LinkedBlockingDeque<>();
        return new KeyAndResults(keySearch, results);
    }

    private void startAllSearch(KeyAndResults keyAndResults, List<CanBeSearchedT> canBeSearched) {
        canBeSearched.stream().forEach(beSearched -> {
            asyncSearchOne(keyAndResults, beSearched);
        });
    }

    private void asyncSearchOne(KeyAndResults keyAndResults, CanBeSearchedT canBeSearched) {
        searchService.execute(() -> {
            MessageOfSearch<ResultT, CanBeSearchedT> messageOfSearch = searchModel.search(keyAndResults.keySearch, canBeSearched);
            saveSatisfyResultsIfExist(keyAndResults, messageOfSearch);
            continueSearchIfExist(keyAndResults, messageOfSearch);
        });
    }

    private void saveSatisfyResultsIfExist(KeyAndResults keyAndResults, MessageOfSearch<ResultT, CanBeSearchedT> messageOfSearch) {
        Optional<List<ResultT>> resultsOptional = messageOfSearch.getTrueResult();
        if (resultsOptional.isPresent()) {
            saveTrueResult(keyAndResults, resultsOptional);
        }
    }

    private void continueSearchIfExist(KeyAndResults keyAndResults, MessageOfSearch<ResultT, CanBeSearchedT> messageOfSearch) {
        Optional<List<CanBeSearchedT>> canBeSearchedOptional = messageOfSearch.getCanBeSearched();
        if (canBeSearchedOptional.isPresent()) {
            executeCanBeSearched(keyAndResults, canBeSearchedOptional);
        }
    }
    private void executeCanBeSearched(KeyAndResults keyAndResults, Optional<List<CanBeSearchedT>> canBeSearchedOptional) {
        List<CanBeSearchedT> canBeSearchedTs = canBeSearchedOptional.get();
        startAllSearch(keyAndResults, canBeSearchedTs);
    }

    private void saveTrueResult(KeyAndResults keyAndResults, Optional<List<ResultT>> resultsOptional) {
        List<ResultT> currentResults = resultsOptional.get();
        for (ResultT trueResult : currentResults) {
            saveAResult(keyAndResults, trueResult);
        }
    }
    private void saveAResult(KeyAndResults keyAndResults, ResultT trueResult) {
        try {
            keyAndResults.results.put(trueResult);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public BlockingQueue<ResultT> getResultsBlockingQueue(KeySearchT keySearch) {
        SoftReference<BlockingQueue<ResultT>> results = cacheResults.compute(keySearch);
        return results.get();
    }

    @Override
    public List<ResultT> getResultsUntilOneTimeout(KeySearchT keySearchT, long timeout, TimeUnit unit) {
        RuleParameter ruleParameter = preparatoryWorkBeforeGetResult(keySearchT, timeout, unit, NOT_LIMIT_EXPECT_NUM);

        List list = startGetResultsUntilOneTimeout(ruleParameter);
        unifyResultCache(ruleParameter, list);
        return list;
    }

    private RuleParameter preparatoryWorkBeforeGetResult(KeySearchT keySearchT, long timeout, TimeUnit unit, int expectNum) {
        BlockingQueue<ResultT> resultBlockingQueue = getResultsBlockingQueue(keySearchT);
        long milliTimeout = preventTimeoutTooLong(timeout, unit);
        return getRuleParameter(milliTimeout, expectNum, resultBlockingQueue);
    }

    private List<ResultT> startGetResultsUntilOneTimeout(RuleParameter ruleParameter) {
        List<ResultT> list = new ArrayList<>();
        boolean notTimeout = true;
        while (notTimeout) {
            notTimeout = addToListUntilOneTimeout(list, ruleParameter);
        }
        return list;
    }

    private void unifyResultCache(RuleParameter ruleParameter, List list) {
        list.stream().forEach(result -> {
            ruleParameter.resultTBlockingQueue.offer(result);
        });
    }

    private boolean addToListUntilOneTimeout(List<ResultT> list, RuleParameter<ResultT> rule) {
        ResultT result = null;
        try {
            result = rule.resultTBlockingQueue.poll(rule.milliTimeout, rule.unit);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        if (isNotTimeout(result)) {
            list.add(result);
        } else {
            return false;
        }
        return true;
    }

    private boolean isNotTimeout(ResultT result) {
        return result != null;
    }

    @Override
    public List<ResultT> getResultsUntilTimeout(KeySearchT keySearchT, long timeout, TimeUnit unit) {
        RuleParameter ruleParameter = preparatoryWorkBeforeGetResult(keySearchT, timeout, unit, NOT_LIMIT_EXPECT_NUM);

        final List<ResultT> resultList = new ArrayList<>();
        Future timingCancelFuture = submitToAddResultToList(resultList, ruleParameter);
        startTimingCancel(timingCancelFuture, ruleParameter);
        unifyResultCache(ruleParameter, resultList);
        return resultList;
    }

    private Future<Object> submitToAddResultToList(List<ResultT> resultList, RuleParameter<ResultT> rule) {
        return gitService.submit(() -> {
            while (true) {
                resultList.add(rule.resultTBlockingQueue.take());
            }
        });
    }

    private void startTimingCancel(Future timingCancelFuture, RuleParameter rule) {
        try {
            timingCancelFuture.get(rule.milliTimeout, rule.unit);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {

        } finally {
            timingCancelFuture.cancel(true);
        }
    }

    @Override
    public List<ResultT> getResultsUntilEnoughOrTimeout(KeySearchT keySearchT, int expectNum, long timeout, TimeUnit unit) {
        RuleParameter ruleParameter = preparatoryWorkBeforeGetResult(keySearchT, timeout, unit, NOT_LIMIT_EXPECT_NUM);

        final List<ResultT> resultList = new ArrayList<>();
        Future timingCancelFuture = startAddResultToListUntilEnough(resultList, ruleParameter);
        startTimingCancel(timingCancelFuture, ruleParameter);
        unifyResultCache(ruleParameter, resultList);
        return resultList;
    }

    private Future startAddResultToListUntilEnough(List<ResultT> resultList, RuleParameter<ResultT> rule) {
        return gitService.submit(() -> {
            for(int i = 0; i < rule.expectNum; i++) {
                try {
                    ResultT resultT = rule.resultTBlockingQueue.take();
                    resultList.add(resultT);
                } catch (InterruptedException e) {

                }
            }
        });
    }

    @Override
    public List<ResultT> getResultsUntilEnoughOrGitOneTimeout(KeySearchT keySearchT, int expectNum, long timeout, TimeUnit unit) {
        RuleParameter ruleParameter = preparatoryWorkBeforeGetResult(keySearchT, timeout, unit, expectNum);

        List list = startGetResultsUntilEnoughOrOneTimeout(ruleParameter);
        unifyResultCache(ruleParameter, list);
        return list;
    }

    private List startGetResultsUntilEnoughOrOneTimeout(RuleParameter ruleParameter) {
        List<ResultT> list = new ArrayList<>();
        boolean notTimeout = true;
        while (notTimeout) {
            notTimeout = addToListUntilEnoughOrOneTimeout(list, ruleParameter);
        }
        return list;
    }

    private boolean addToListUntilEnoughOrOneTimeout(List<ResultT> list, RuleParameter<ResultT> rule) {
        ResultT result;
        if (list.size() >= rule.expectNum) {
            return false;
        }
        try {
            result = rule.resultTBlockingQueue.poll(rule.milliTimeout, rule.unit);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        if (isNotTimeout(result)) {
            list.add(result);
        } else {
            return false;
        }
        return true;
    }

    @Override
    public List<ResultT> getResultsUntilEnough(KeySearchT keySearchT, int expectNum) {
        RuleParameter<ResultT> rule = preparatoryWorkBeforeGetResult(keySearchT, NOT_HAVE_TIMEOUT, null, expectNum);
        List<ResultT> list = startGetResultsUntilEnough(rule);
        unifyResultCache(rule, list);
        return list;
    }

    private List<ResultT> startGetResultsUntilEnough(RuleParameter<ResultT> rule) {
        List<ResultT> list = new ArrayList<>();
        ResultT resultT;
        while (list.size() < rule.expectNum) {
            resultT = takeOfQueueWithTryCatch(rule.resultTBlockingQueue);
            list.add(resultT);
        }
        return list;
    }

    private ResultT takeOfQueueWithTryCatch(BlockingQueue<ResultT> resultTBlockingQueue) {
        try {
            return resultTBlockingQueue.take();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public ResultT getAResult(KeySearchT keySearch) {
        BlockingQueue<ResultT> resultTBlockingQueue = getResultsBlockingQueue(keySearch);
        ResultT resultT = takeOfQueueWithTryCatch(resultTBlockingQueue);
        unifyResultCache(resultT, resultTBlockingQueue);
        return resultT;
    }

    private void unifyResultCache(ResultT resultT, BlockingQueue queue) {
        queue.offer(resultT);
    }

    @Override
    public ResultT getAResultUntilTimeout(KeySearchT keySearchT, long timeout, TimeUnit timeUnit) throws TimeoutException {
        RuleParameter<ResultT> ruleParameter = preparatoryWorkBeforeGetResult(keySearchT, timeout, timeUnit, NOT_LIMIT_EXPECT_NUM);

        ResultT resultT = startGetAResultUntilTimeout(ruleParameter);
        unifyResultCache(resultT, ruleParameter.resultTBlockingQueue);
        return resultT;
    }

    private ResultT startGetAResultUntilTimeout(RuleParameter<ResultT> ruleParameter) {
        List<ResultT> saveResult = new ArrayList<>();
        Future future = gitService.submit(() -> {
            ResultT resultT =takeOfQueueWithTryCatch(ruleParameter.resultTBlockingQueue);
            saveResult.add(resultT);
        });
        startTimingCancel(future, ruleParameter);
        ResultT resultT = null;
        if (saveResult.size() != 0) {
            resultT = saveResult.get(0);
        }
        return resultT;
    }

    @Override
    public void remove(ResultT value) {
        boolean result = searchModel.remove(value);
        clearCacheIfSuccess(result);
    }

    @Override
    public void add(ResultT value) {
        boolean result = searchModel.add(value);
        clearCacheIfSuccess(result);
    }

    @Override
    public void clearCache() {
        cacheResults.clearCache();
    }

    public boolean isEmpty() {
        return cacheResults.isEmpty();
    }

    public void stopSearch() {
        searchService.shutdown();
    }

    public void stopSearchNow() {
        searchService.shutdownNow();
    }

    private class KeyAndResults {

        final BlockingQueue<ResultT> results;

        final KeySearchT keySearch;

        public KeyAndResults(KeySearchT keySearch, BlockingQueue<ResultT> results) {
            this.results = results;
            this.keySearch = keySearch;
        }

    }

    private void clearCacheIfSuccess(boolean result) {
        if (result) {
            clearCache();
        }
    }

    private long preventTimeoutTooLong(long milliTimeout, TimeUnit unit) {
        long currentTimeout = unit.toMillis(milliTimeout);
        if (currentTimeout > MAX_WAIT_MILLISECOND) {
            currentTimeout = MAX_WAIT_MILLISECOND;
        }
        return currentTimeout;
    }

    private RuleParameter getRuleParameter(long milliTimeout, int expectNum, BlockingQueue resultQueue) {
        RuleParameter<ResultT> ruleParameter = new RuleParameter(resultQueue, milliTimeout, expectNum);
        return ruleParameter;
    }
}