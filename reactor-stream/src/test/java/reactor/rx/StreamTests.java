/*
 * Copyright (c) 2011-2015 Pivotal Software Inc., Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.rx;

import org.hamcrest.Matcher;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.AbstractReactorTest;
import reactor.Environment;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.bus.selector.Selector;
import reactor.bus.selector.Selectors;
import reactor.core.Dispatcher;
import reactor.core.DispatcherSupplier;
import reactor.core.dispatch.SynchronousDispatcher;
import reactor.core.processor.RingBufferProcessor;
import reactor.fn.Consumer;
import reactor.fn.Function;
import reactor.fn.support.Tap;
import reactor.jarjar.com.lmax.disruptor.BlockingWaitStrategy;
import reactor.jarjar.com.lmax.disruptor.dsl.ProducerType;
import reactor.rx.action.Action;
import reactor.rx.action.Control;
import reactor.rx.broadcast.Broadcaster;
import reactor.rx.stream.BarrierStream;
import reactor.rx.subscription.PushSubscription;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.OrderingComparison.lessThan;
import static org.junit.Assert.*;
import static reactor.bus.selector.Selectors.$;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class StreamTests extends AbstractReactorTest {

	static final String2Integer STRING_2_INTEGER = new String2Integer();

	@Test
	public void testComposeFromSingleValue() throws InterruptedException {
		Stream<String> stream = Streams.just("Hello World!");
		Stream<String> s =
				stream
						.map(s1 -> "Goodbye then!");

		await(s, is("Goodbye then!"));
	}

	@Test
	public void testComposeFromMultipleValues() throws InterruptedException {
		Stream<String> stream = Streams.just("1", "2", "3", "4", "5");
		Stream<Integer> s =
				stream
						.map(STRING_2_INTEGER)
						.map(new Function<Integer, Integer>() {
							int sum = 0;

							@Override
							public Integer apply(Integer i) {
								sum += i;
								return sum;
							}
						});
		await(5, s, is(15));
	}

	@Test
	public void simpleReactiveSubscriber() throws InterruptedException {
		Broadcaster<String> str = Broadcaster.create(env);

		str.subscribe(new TestSubscriber());

		System.out.println(str.debug());


		str.onNext("Goodbye World!");
		str.onNext("Goodbye World!");
		str.onComplete();

		Thread.sleep(500);
	}

	@Test
	public void testComposeFromMultipleFilteredValues() throws InterruptedException {
		Stream<String> stream = Streams.just("1", "2", "3", "4", "5");
		Stream<Integer> s =
				stream
						.map(STRING_2_INTEGER)
						.filter(i -> i % 2 == 0);

		await(2, s, is(4));
	}

	@Test
	public void testComposedErrorHandlingWithMultipleValues() throws InterruptedException {
		Stream<String> stream = Streams.just("1", "2", "3", "4", "5");

		final AtomicBoolean exception = new AtomicBoolean(false);
		Stream<Integer> s =
				stream
						.map(STRING_2_INTEGER)
						.map(new Function<Integer, Integer>() {
							int sum = 0;

							@Override
							public Integer apply(Integer i) {
								if (i >= 5) {
									throw new IllegalArgumentException();
								}
								sum += i;
								return sum;
							}
						})
						.when(IllegalArgumentException.class, e -> exception.set(true));

		await(5, s, is(10));
		assertThat("exception triggered", exception.get(), is(true));
	}


	@Test
	public void testComposedErrorHandlingWitIgnoreErrors() throws InterruptedException {
		Stream<String> stream = Streams.just("1", "2", "3", "4", "5");

		final AtomicBoolean exception = new AtomicBoolean(false);
		Stream<Integer> s =
				stream
						.map(STRING_2_INTEGER)
						.observe(i -> {
							if (i == 3)
								throw new IllegalArgumentException();
						})
						.ignoreError()
						.log()
						.map(new Function<Integer, Integer>() {
							int sum = 0;

							@Override
							public Integer apply(Integer i) {
								sum += i;
								return sum;
							}
						})
						.when(IllegalArgumentException.class, e -> exception.set(true));

		await(2, s, is(3));
		assertThat("exception triggered", exception.get(), is(false));
	}

	@Test
	public void testReduce() throws InterruptedException {
		Stream<String> stream = Streams.just("1", "2", "3", "4", "5");
		Stream<Integer> s =
				stream
						.map(STRING_2_INTEGER)
						.reduce(1, (acc, next) -> acc * next);
		await(1, s, is(120));
	}

	@Test
	public void testMerge() throws InterruptedException {
		Stream<String> stream1 = Streams.just("1", "2");
		Stream<String> stream2 = Streams.just("3", "4", "5");
		Stream<Integer> s =
				Streams.merge(stream1, stream2)
						//.dispatchOn(env)
						.capacity(5)
						.map(STRING_2_INTEGER)
						.reduce(1, (acc, next) -> acc * next);
		await(1, s, is(120));
	}

	@Test
	public void testFirstAndLast() throws InterruptedException {
		Stream<Integer> s = Streams.from(Arrays.asList(1, 2, 3, 4, 5));

		Stream<Integer> first = s.sampleFirst();
		Stream<Integer> last = s.sample();

		assertThat("First is 1", first.tap().get(), is(1));
		assertThat("Last is 5", last.tap().get(), is(5));
	}

	@Test
	public void testRelaysEventsToReactor() throws InterruptedException {
		EventBus r = EventBus.config().get();
		Selector key = Selectors.$();

		final CountDownLatch latch = new CountDownLatch(5);
		final Tap<Event<Integer>> tap = new Tap<Event<Integer>>() {
			@Override
			public void accept(Event<Integer> integerEvent) {
				super.accept(integerEvent);
				latch.countDown();
			}
		};

		r.on(key, tap);

		r.notify(Streams.just("1", "2", "3", "4", "5").map(STRING_2_INTEGER), key.getObject());

		//await(s, is(5));
		assertThat("latch was counted down", latch.getCount(), is(0l));
		assertThat("value is 5", tap.get().getData(), is(5));
	}

	@Test
	public void testStreamBatchesResults() {
		Stream<String> stream = Streams.just("1", "2", "3", "4", "5");
		Stream<List<Integer>> s =
				stream
						.map(STRING_2_INTEGER)
						.buffer();

		final AtomicInteger batchCount = new AtomicInteger();
		final AtomicInteger count = new AtomicInteger();
		s.consume(is -> {
			batchCount.incrementAndGet();
			for (int i : is) {
				count.addAndGet(i);
			}
		});

		assertThat("batchCount is 3", batchCount.get(), is(1));
		assertThat("count is 15", count.get(), is(15));
	}

	@Test
	public void testHandlersErrorsDownstream() throws InterruptedException {
		Stream<String> stream = Streams.just("1", "2", "a", "4", "5");
		final CountDownLatch latch = new CountDownLatch(1);
		Stream<Integer> s =
				stream
						.map(STRING_2_INTEGER)
						.map(new Function<Integer, Integer>() {
							int sum = 0;

							@Override
							public Integer apply(Integer i) {
								if (i >= 5) {
									throw new IllegalArgumentException();
								}
								sum += i;
								return sum;
							}
						})
						.when(NumberFormatException.class, new Consumer<NumberFormatException>() {
							@Override
							public void accept(NumberFormatException e) {
								latch.countDown();
							}
						});

		await(2, s, is(3));
		assertThat("error handler was invoked", latch.getCount(), is(0L));
	}

	@Test
	public void promiseAcceptCountCannotExceedOne() {
		Promise<Object> deferred = Promises.<Object>prepare();
		deferred.onNext("alpha");
		try {
			deferred.onNext("bravo");
		} catch (IllegalStateException ise) {
			// Swallow
		}
		assertEquals(deferred.get(), "alpha");
	}

	@Test
	public void promiseErrorCountCannotExceedOne() {
		Promise<Object> deferred = Promises.prepare();
		Throwable error = new Exception();
		deferred.onError(error);
		try {
			deferred.onNext(error);
		} catch (IllegalStateException ise) {
			// Swallow
		}
		assertTrue(deferred.reason() instanceof Exception);
	}

	@Test
	public void promiseAcceptCountAndErrorCountCannotExceedOneInTotal() {
		Promise<Object> deferred = Promises.prepare();
		Throwable error = new Exception();
		deferred.onError(error);
		try {
			deferred.onNext("alpha");
			fail();
		} catch (IllegalStateException ise) {
		}
		assertTrue(deferred.reason() instanceof Exception);
	}

	@Test
	public void mapManyFlushesAllValuesThoroughly() throws InterruptedException {
		int items = 1000;
		CountDownLatch latch = new CountDownLatch(items);
		Random random = ThreadLocalRandom.current();

		Broadcaster<String> d = Broadcaster.<String>create(env);
		Stream<Integer> tasks = d.partition().flatMap(stream ->
				stream.dispatchOn(Environment.cachedDispatcher()).map((String str) -> {
					try {
						Thread.sleep(random.nextInt(10));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return Integer.parseInt(str);
				}));

		Control tail = tasks.consume(i -> {
			latch.countDown();
		});

		System.out.println(tail.debug());

		for (int i = 1; i <= items; i++) {
			d.onNext(String.valueOf(i));
		}
		latch.await(15, TimeUnit.SECONDS);
		System.out.println(tail.debug());
		assertTrue(latch.getCount() + " of " + items + " items were not counted down", latch.getCount() == 0);
	}

	@Test
	public void mapManyFlushesAllValuesConsistently() throws InterruptedException {
		int iterations = 5;
		for (int i = 0; i < iterations; i++) {
			mapManyFlushesAllValuesThoroughly();
		}
	}

	<T> void await(Stream<T> s, Matcher<T> expected) throws InterruptedException {
		await(1, s, expected);
	}

	<T> void await(int count, final Stream<T> s, Matcher<T> expected) throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(count);
		final AtomicReference<T> ref = new AtomicReference<T>();
		Control control = s.when(Exception.class, e -> {
			e.printStackTrace();
			latch.countDown();
		}).consume(t -> {
			ref.set(t);
			latch.countDown();
		});

		long startTime = System.currentTimeMillis();
		T result = null;
		try {
			latch.await(10, TimeUnit.SECONDS);
			System.out.println(control.debug());
			result = ref.get();
		} catch (Exception e) {
			e.printStackTrace();
		}
		long duration = System.currentTimeMillis() - startTime;

		assertThat(result, expected);
		assertThat(duration, is(lessThan(2000L)));
	}

	static class String2Integer implements Function<String, Integer> {
		@Override
		public Integer apply(String s) {
			return Integer.parseInt(s);
		}
	}

	@Test
	public void mapNotifiesOnceConsistent() throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			System.out.println("iteration");
			mapNotifiesOnce();
		}
	}

	/**
	 * See #294 the consumer received more or less calls than expected Better reproducible with big thread pools,
	 * e.g. 128
	 * threads
	 *
	 * @throws InterruptedException
	 */
	@Test
	public void mapNotifiesOnce() throws InterruptedException {

		final int COUNT = 10000;
		final Object internalLock = new Object();
		final Object consumerLock = new Object();

		final CountDownLatch internalLatch = new CountDownLatch(COUNT);
		final CountDownLatch counsumerLatch = new CountDownLatch(COUNT);

		final AtomicInteger internalCounter = new AtomicInteger(0);
		final AtomicInteger consumerCounter = new AtomicInteger(0);

		final ConcurrentHashMap<Object, Long> seenInternal = new ConcurrentHashMap<>();
		final ConcurrentHashMap<Object, Long> seenConsumer = new ConcurrentHashMap<>();

		Broadcaster<Integer> d = Broadcaster.create(env);

		Control c = d
				.partition().consume(stream ->
						stream.dispatchOn(Environment.cachedDispatcher())
								.map(o -> {
									synchronized (internalLock) {

										internalCounter.incrementAndGet();

										long curThreadId = Thread.currentThread().getId();
										Long prevThreadId = seenInternal.put(o, curThreadId);
										if (prevThreadId != null) {
											fail(String.format(
													"The object %d has already been seen internally on the thread %d, current thread %d",
													o, prevThreadId, curThreadId));
										}

										internalLatch.countDown();
									}
									return -o;
								})
								.consume(o -> {
									synchronized (consumerLock) {
										consumerCounter.incrementAndGet();

										long curThreadId = Thread.currentThread().getId();
										Long prevThreadId = seenConsumer.put(o, curThreadId);
										if (prevThreadId != null) {
											System.out.println(String.format(
													"The object %d has already been seen by the consumer on the thread %d, current thread %d",
													o, prevThreadId, curThreadId));
											fail();
										}

										counsumerLatch.countDown();
									}
								}));


		for (int i = 0; i < COUNT; i++) {
			d.onNext(i);
		}

		internalLatch.await(5, TimeUnit.SECONDS);
		System.out.println(c.debug());
		assertEquals(COUNT, internalCounter.get());
		counsumerLatch.await(5, TimeUnit.SECONDS);
		assertEquals(COUNT, consumerCounter.get());
	}


	@Test
	public void parallelTests() throws InterruptedException {
		parallelMapManyTest("shared", 1_000_000);
		parallelTest("sync", 1_000_000);
		parallelMapManyTest("sync", 1_000_000);
		parallelTest("shared", 1_000_000);
		parallelTest("partitioned", 1_000_000);
		parallelMapManyTest("partitioned", 1_000_000);
		parallelBufferedTimeoutTest(1_000_000);
	}

	private void parallelBufferedTimeoutTest(int iterations) throws InterruptedException {


		System.out.println("Buffered Stream: " + iterations);

		final CountDownLatch latch = new CountDownLatch(iterations);

		Broadcaster<String> deferred = Broadcaster.<String>create(env);
		deferred
				.partition()
				.consume(stream ->
						stream
								.dispatchOn(env.getCachedDispatcher())
								.buffer(1000 / 8, 1l, TimeUnit.SECONDS)
								.consume(batch -> {
									for (String i : batch) latch.countDown();
								}));

		String[] data = new String[iterations];
		for (int i = 0; i < iterations; i++) {
			data[i] = Integer.toString(i);
		}

		long start = System.currentTimeMillis();

		for (String i : data) {
			deferred.onNext(i);
		}
		if (!latch.await(30, TimeUnit.SECONDS))
			throw new RuntimeException(deferred.debug().toString());


		long stop = System.currentTimeMillis() - start;
		stop = stop > 0 ? stop : 1;

		System.out.println("Time spent: " + stop + "ms");
		System.out.println("ev/ms: " + iterations / stop);
		System.out.println("ev/s: " + iterations / stop * 1000);
		System.out.println("");
		System.out.println(deferred.debug());
		assertEquals(0, latch.getCount());
	}

	private void parallelTest(String dispatcher, int iterations) throws InterruptedException {


		System.out.println("Dispatcher: " + dispatcher);
		System.out.println("..........:  " + iterations);

		int[] data;
		CountDownLatch latch = new CountDownLatch(iterations);
		Action<Integer, Integer> deferred;
		switch (dispatcher) {
			case "partitioned":
				deferred = Broadcaster.create(env);
				System.out.println(deferred.partition(2).consume(stream -> stream
						.dispatchOn(env.getCachedDispatcher())
						.map(i -> i)
						.scan(1, (acc, next) -> acc + next)
						.consume(i -> latch.countDown()).debug()));

				break;

			default:
				deferred = Broadcaster.<Integer>create(env, env.getDispatcher(dispatcher));
				deferred
						.map(i -> i)
						.scan(1, (acc, next) -> acc + next)
						.consume(i -> latch.countDown());
		}

		data = new int[iterations];
		for (int i = 0; i < iterations; i++) {
			data[i] = i;
		}

		long start = System.currentTimeMillis();
		for (int i : data) {
			deferred.onNext(i);
		}

		if (!latch.await(15, TimeUnit.SECONDS)) {
			throw new RuntimeException("Count:" + (iterations - latch.getCount()) + " " + deferred.debug().toString());
		}

		long stop = System.currentTimeMillis() - start;
		stop = stop > 0 ? stop : 1;

		System.out.println("Time spent: " + stop + "ms");
		System.out.println("ev/ms: " + iterations / stop);
		System.out.println("ev/s: " + iterations / stop * 1000);
		System.out.println("");
		System.out.println(deferred.debug());
		assertEquals(0, latch.getCount());

	}

	private void parallelMapManyTest(String dispatcher, int iterations) throws InterruptedException {

		System.out.println("MM Dispatcher: " + dispatcher);
		System.out.println("..........:  " + iterations);

		int[] data;
		CountDownLatch latch = new CountDownLatch(iterations);
		Action<Integer, Integer> mapManydeferred;
		switch (dispatcher) {
			case "partitioned":
				mapManydeferred = Broadcaster.<Integer>create();
				mapManydeferred
						.partition(4).consume(substream ->
						substream.dispatchOn(env.getCachedDispatcher()).consume(i -> latch.countDown()));
				break;
			default:
				Dispatcher dispatcher1 = env.getDispatcher(dispatcher);
				mapManydeferred = Broadcaster.<Integer>create(env, dispatcher1);
				mapManydeferred
						.flatMap(Streams::just)
						.consume(i -> latch.countDown());
		}

		data = new int[iterations];
		for (int i = 0; i < iterations; i++) {
			data[i] = i;
		}

		long start = System.currentTimeMillis();

		for (int i : data) {
			mapManydeferred.onNext(i);
		}

		if (!latch.await(20, TimeUnit.SECONDS)) {
			throw new RuntimeException(mapManydeferred.debug().toString());
		} else {
			System.out.println(mapManydeferred.debug().toString());
		}
		assertEquals(0, latch.getCount());


		long stop = System.currentTimeMillis() - start;
		stop = stop > 0 ? stop : 1;

		System.out.println("Dispatcher: " + dispatcher);
		System.out.println("Time spent: " + stop + "ms");
		System.out.println("ev/ms: " + iterations / stop);
		System.out.println("ev/s: " + iterations / stop * 1000);
		System.out.println("");
	}

	/**
	 * See https://github.com/reactor/reactor/issues/451
	 */
	@Test
	public void partitionByHashCodeShouldNeverCreateMoreStreamsThanSpecified() throws Exception {
		Stream<Integer> stream = Streams.range(-10, 10).map(Long::intValue);

		assertThat(stream.partition(2).count().tap().get(), is(equalTo(2L)));
	}


	/**
	 * original from @oiavorskyl
	 * https://github.com/eventBus/eventBus/issues/358
	 *
	 * @throws Exception
	 */
	//@Test
	public void shouldNotFlushStreamOnTimeoutPrematurelyAndShouldDoItConsistently() throws Exception {
		for (int i = 0; i < 100; i++) {
			shouldNotFlushStreamOnTimeoutPrematurely();
		}
	}

	/**
	 * original from @oiavorskyl
	 * https://github.com/eventBus/eventBus/issues/358
	 *
	 * @throws Exception
	 */
	@Test
	public void shouldNotFlushStreamOnTimeoutPrematurely() throws Exception {
		final int NUM_MESSAGES = 1000000;
		final int BATCH_SIZE = 1000;
		final int TIMEOUT = 100;
		final int PARALLEL_STREAMS = 2;

		/**
		 * Relative tolerance, default to 90% of the batches, in an operative environment, random factors can impact
		 * the stream latency, e.g. GC pause if system is under pressure.
		 */
		final double TOLERANCE = 0.9;


		Broadcaster<Integer> batchingStreamDef = Broadcaster.create(env);

		List<Integer> testDataset = createTestDataset(NUM_MESSAGES);

		final CountDownLatch latch = new CountDownLatch(NUM_MESSAGES);
		Map<Integer, Integer> batchesDistribution = new ConcurrentHashMap<>();
		batchingStreamDef.partition(PARALLEL_STREAMS).consume(substream ->
				substream
						.dispatchOn(env.getCachedDispatcher())
						.buffer(BATCH_SIZE, TIMEOUT, TimeUnit.MILLISECONDS)
						.consume(items -> {
							batchesDistribution.compute(items.size(),
									(key,
									 value) -> value == null ? 1 : value + 1);
							items.forEach(item -> latch.countDown());
						}));

		testDataset.forEach(batchingStreamDef::onNext);
		if (!latch.await(10, TimeUnit.SECONDS)) {
			throw new RuntimeException(latch.getCount() + " " + batchingStreamDef.debug().toString());

		}

		int messagesProcessed = batchesDistribution.entrySet()
				.stream()
				.mapToInt(entry -> entry.getKey() * entry
						.getValue())
				.reduce(Integer::sum).getAsInt();

		System.out.println(batchingStreamDef.debug());

		assertEquals(NUM_MESSAGES, messagesProcessed);
		System.out.println(batchesDistribution);
		assertTrue("Less than 90% (" + NUM_MESSAGES / BATCH_SIZE * TOLERANCE +
						") of the batches are matching the buffer() size: " + batchesDistribution.get(BATCH_SIZE),
				NUM_MESSAGES / BATCH_SIZE * TOLERANCE >= batchesDistribution.get(BATCH_SIZE) * TOLERANCE);
	}


	@Test
	public void shouldCorrectlyDispatchComplexFlow() throws InterruptedException {
		Broadcaster<Integer> globalFeed = Broadcaster.create(env);

		CountDownLatch afterSubscribe = new CountDownLatch(1);
		CountDownLatch latch = new CountDownLatch(4);

		Stream<Integer> s = Streams.just("2222")
				.map(Integer::parseInt)
				.flatMap(l ->
								Streams.<Integer>merge(
										globalFeed,
										Streams.just(1111, l, 3333, 4444, 5555, 6666)
								)
										.log("merged")
										.dispatchOn(env)
										.log("dispatched")
										.observeSubscribe(x -> afterSubscribe.countDown())
										.filter(nearbyLoc -> 3333 >= nearbyLoc)
										.filter(nearbyLoc -> 2222 <= nearbyLoc)

				);

		Action<Integer, Void> action = s.broadcastTo(new Action<Integer, Void>() {
			Subscription s;

			@Override
			public void doOnSubscribe(Subscription s) {
				this.s = s;
				s.request(1);
			}

			@Override
			public void doNext(Integer integer) {
				System.out.println(Thread.currentThread().getName() + " " + debug());
				latch.countDown();
				System.out.println(integer);
				s.request(1);
			}

			@Override
			public void doError(Throwable t) {
				t.printStackTrace();
			}

			@Override
			public void doComplete() {
				System.out.println(Thread.currentThread().getName() + " complete " + debug());
			}
		});

		System.out.println(action.debug());

		afterSubscribe.await(5, TimeUnit.SECONDS);

		System.out.println(action.debug());

		globalFeed.onNext(2223);
		globalFeed.onNext(2224);

		latch.await(5, TimeUnit.SECONDS);
		System.out.println(action.debug());
		assertEquals("Must have counted 4 elements", 0, latch.getCount());

	}

	@Test
	public void testParallelAsyncStream2() throws InterruptedException {

		final int numOps = 25;

		CountDownLatch latch = new CountDownLatch(numOps);

		Stream<String> operationStream =
				Streams.defer(() -> Broadcaster.<String>create(Environment.cachedDispatcher()))
						.throttle(100)
						.map(s -> s + " MODIFIED")
						.map(s -> {
							latch.countDown();
							return s;
						})
				//.log();
				;


		for (int i = 0; i < numOps; i++) {
			final String source = "ASYNC_TEST " + i;

			Streams.just(source)
					.broadcastTo(operationStream.combine())
					.take(2, TimeUnit.SECONDS)
					.log()
					.consume(System.out::println);
		}

		latch.await();
		assertEquals(0, latch.getCount());
	}


	/**
	 * https://gist.github.com/nithril/444d8373ce67f0a8b853
	 * Contribution by Nicolas Labrot
	 */
	@Test
	public void testParallelWithJava8StreamsInput() throws InterruptedException {
		DispatcherSupplier supplier =
				Environment.createDispatcherFactory("test-p", 2, 2048, null, ProducerType.MULTI, new BlockingWaitStrategy());

		int max = ThreadLocalRandom.current().nextInt(100, 300);
		CountDownLatch countDownLatch = new CountDownLatch(max + 1);

		Stream<Long> worker = Streams.range(0, max).dispatchOn(env);
		worker.partition(2).consume(s ->
						s
								.dispatchOn(supplier.get())
								.map(v -> v)
								.consume(v -> countDownLatch.countDown())
		);

		countDownLatch.await(10, TimeUnit.SECONDS);
		Assert.assertEquals(0, countDownLatch.getCount());
	}

	@Test
	public void testBeyondLongMaxMicroBatching() throws InterruptedException {
		List<Integer> tasks = IntStream.range(0, 1500).boxed().collect(Collectors.toList());

		CountDownLatch countDownLatch = new CountDownLatch(tasks.size());
		Stream<Integer> worker = Streams.from(tasks).dispatchOn(env);

		Control tail = worker.partition(2).consume(s ->
						s
								.dispatchOn(env.getCachedDispatcher())
								.map(v -> v)
								.consume(v -> countDownLatch.countDown(),
										Throwable::printStackTrace)
		);

		countDownLatch.await(5, TimeUnit.SECONDS);
		if (countDownLatch.getCount() > 0) {
			System.out.println(tail.debug());
		}
		Assert.assertEquals(0, countDownLatch.getCount());
	}


	@Test
	@Ignore
	public void testCustomFileStream() throws InterruptedException {

		Processor<String, String> broad = RingBufferProcessor.create();
		broad.onNext("test");
		Streams.wrap(broad).consume(System.out::println);
		Thread.sleep(5000);

		Stream<String> fileStream = new Stream<String>() {
			@Override
			public void subscribe(final Subscriber<? super String> subscriber) {
				final File file = new File("settings.gradle");

				try {
					final BufferedReader is = new BufferedReader(new FileReader(file));

					subscriber.onSubscribe(new PushSubscription<String>(this, subscriber) {

						@Override
						protected void onRequest(long n) {
							long requestCursor = 0l;
							try {
								String line;
								while (requestCursor++ < n || n == Long.MAX_VALUE) {
									line = is.readLine();
									if(line != null) {
										onNext(line);
									} else {
										is.close();
										onComplete();
										return;
									}
								}
							} catch (IOException e) {
								onError(e);
							}
						}

						@Override
						public void cancel() {
							try {
								is.close();
							} catch (IOException e) {
								onError(e);
							}
						}
					});

				} catch (FileNotFoundException e) {
					Streams.<String, FileNotFoundException>fail(e)
							.subscribe(subscriber);
				}
			}
		};

		fileStream
				.capacity(4L)
				.consumeOn(
						Environment.sharedDispatcher(),
						System.out::println,
						Throwable::printStackTrace,
						nothing -> System.out.println("## EOF ##")
				);
	}

	@Test
	public void shouldWindowCorrectly() throws InterruptedException {
		Stream<Integer> sensorDataStream =
				Streams.from(createTestDataset(1000))
						.dispatchOn(env, SynchronousDispatcher.INSTANCE);

		CountDownLatch endLatch = new CountDownLatch(1000 / 100);

		Control controls = sensorDataStream
				/*     step 2  */.window(100)
				///*     step 3  */.timeout(1000)
				/*     step 4  */.consume(batchedStream -> {
			System.out.println("New window starting");
			batchedStream
						/*   step 4.1  */.reduce(Integer.MAX_VALUE, (acc, next) -> Math.min(acc, next))
						/* ad-hoc step */.finallyDo(o -> endLatch.countDown())
						/* final step  */.consume(i -> System.out.println("Minimum " + i));
		});

		endLatch.await(10, TimeUnit.SECONDS);
		System.out.println(controls.debug());

		Assert.assertEquals(0, endLatch.getCount());
	}

	@Test
	public void shouldThrottleCorrectly() throws InterruptedException {
		Streams.range(1, 10000000)
				.dispatchOn(Environment.sharedDispatcher())
				.requestWhen(reqs -> reqs.flatMap(req -> {
					// set the batch size
					long batchSize = 10;

					// Value below in reality should be req / batchSize;
					// Now test for 30, 60, 500, 1000, 1000000 etc and see what happens
					// small nubmers should work, but I think there is bug
					long numBatches = 1000;

					System.out.println("Original request = " + req);
					System.out.println("Batch size = " + batchSize);
					System.out.println("Number of batches should be = " + numBatches);

					// LongRangeStream for correct handling of values > 4 byte int
					// return new LongRangeStream(1, numBatches).map(x->batchSize);
					return Streams.range(1, (int) numBatches).map(x -> batchSize);
				}))
				.consume();
	}

	@Test
	public void shouldCorrectlyDispatchBatchedTimeout() throws InterruptedException {

		long timeout = 100;
		final int batchsize = 4;
		int parallelStreams = 16;
		CountDownLatch latch = new CountDownLatch(1);

		final Broadcaster<Integer> streamBatcher = Broadcaster.<Integer>create(env);
		streamBatcher
				.buffer(batchsize, timeout, TimeUnit.MILLISECONDS)
				.partition(parallelStreams)
				.consume(innerStream ->
						innerStream
								.dispatchOn(env.getCachedDispatcher())
								.when(Exception.class, Throwable::printStackTrace)
								.consume(i -> latch.countDown()));


		streamBatcher.onNext(12);
		streamBatcher.onNext(123);
		streamBatcher.onNext(42);
		streamBatcher.onNext(666);

		boolean finished = latch.await(2, TimeUnit.SECONDS);
		if (!finished)
			throw new RuntimeException(streamBatcher.debug().toString());
		else {
			System.out.println(streamBatcher.debug().toString());
			assertEquals("Must have correct latch number : " + latch.getCount(), latch.getCount(), 0);
		}
	}

	@Test
	public void mapLotsOfSubAndCancel() throws InterruptedException {
		for (long i = 0; i < 199; i++)
			mapPassThru();
	}

	public void mapPassThru() throws InterruptedException {
		Streams.just(1).map(IDENTITY_FUNCTION);
	}

	@Test
	public void consistentMultithreadingWithPartition() throws InterruptedException {
		List<String> ids = Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

		DispatcherSupplier supplier1 = Environment.newCachedDispatchers(2, "pool1");
		DispatcherSupplier supplier2 = Environment.newCachedDispatchers(5, "pool2");

		CountDownLatch latch = new CountDownLatch(10);

		Streams.from(ids)
				.dispatchOn(Environment.sharedDispatcher())
				.partition(2)
						// here we receive multiple streams
				.flatMap(stream -> stream
								// we need to call dispatch on each stream
								.dispatchOn(supplier1.get())
								.map(s -> s + " " + Thread.currentThread().toString())
				)
				.map(t -> {
					System.out.println("First partition: " + Thread.currentThread() + ", worker=" + t);
					return t;
				})
						// Also tried to do another dispatch but with no success.
//                .dispatchOn(Environment.sharedDispatcher())
				.partition(5)
						// here we receive multiple streams
				.flatMap(stream -> stream
								// we need to call dispatch on each stream
								.dispatchOn(supplier2.get())
								.map(s -> s + " " + Thread.currentThread().toString())
				)
				.dispatchOn(Environment.sharedDispatcher())
						// worker threads should be funneled into the same, specific thread
						// https://groups.google.com/forum/#!msg/reactor-framework/JO0hGftOaZs/20IhESjPQI0J
				.consume(t -> {
					System.out.println("Second partition: " + Thread.currentThread() + ", worker=" + t);
					latch.countDown();
				});


		assertThat("Not totally dispatched", latch.await(30, TimeUnit.SECONDS));
	}

	@Test
	public void barrierStreamWaitsForAllDelegatesToBeInvoked() throws Exception {
		Environment.initializeIfEmpty().assignErrorJournal();

		CountDownLatch latch1 = new CountDownLatch(1);
		CountDownLatch latch2 = new CountDownLatch(1);
		CountDownLatch latch3 = new CountDownLatch(1);

		BarrierStream barrierStream = new BarrierStream(Environment.get(), Environment.cachedDispatcher());

		EventBus bus = EventBus.create(Environment.get());
		bus.on($("hello"), barrierStream.wrap((Event<String> ev) -> {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
			}
			latch1.countDown();
		}));

		Streams.just("Hello World!")
				.map(barrierStream.wrap((Function<String, String>) String::toUpperCase))
				.consume(s -> {
					latch2.countDown();
				});

		barrierStream.consume(vals -> {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
			}
			latch3.countDown();
		});

		bus.notify("hello", Event.wrap("Hello World!"));

		assertThat("EventBus Consumer has been invoked", latch1.await(1, TimeUnit.SECONDS), is(true));
		assertThat("Stream map Function has been invoked", latch2.getCount(), is(0L));
		assertThat("BarrierStreams has published downstream", latch3.await(1, TimeUnit.SECONDS), is(true));
	}

	private static final Function<Integer, Integer> IDENTITY_FUNCTION = new Function<Integer, Integer>() {
		@Override
		public Integer apply(Integer value) {
			return value;
		}
	};

	private List<Integer> createTestDataset(int i) {
		List<Integer> list = new ArrayList<>(i);
		for (int k = 0; k < i; k++) {
			list.add(k);
		}
		return list;
	}

	class TestSubscriber implements Subscriber<String> {

		private final Logger log = LoggerFactory.getLogger(getClass());

		private Subscription subscription;

		@Override
		public void onSubscribe(Subscription subscription) {
			if (null != this.subscription) {
				subscription.cancel();
				return;
			}
			this.subscription = subscription;
			this.subscription.request(1);
		}

		@Override
		public void onNext(String s) {
			if (s.startsWith("GOODBYE")) {
				log.info("This is the end");
			}
			subscription.request(1);
		}

		@Override
		public void onError(Throwable throwable) {
			log.error(throwable.getMessage(), throwable);
		}

		@Override
		public void onComplete() {
			log.info("stream complete");
		}

	}


}
