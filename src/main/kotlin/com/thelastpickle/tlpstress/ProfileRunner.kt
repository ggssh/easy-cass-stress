package com.thelastpickle.tlpstress

import com.datastax.driver.core.ResultSet
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.thelastpickle.tlpstress.profiles.IStressProfile
import com.thelastpickle.tlpstress.profiles.Operation
import com.thelastpickle.tlpstress.samplers.ISampler
import mu.KotlinLogging
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom

private val logger = KotlinLogging.logger {}


/**
 * Single threaded profile runner.
 * One profile runner should be created per thread
 * Logs all errors along the way
 * Keeps track of useful metrics, per thread
 */
class ProfileRunner(val context: StressContext,
                    val profile: IStressProfile,
                    val partitionKeyGenerator: PartitionKeyGenerator,
                    val sampler: ISampler) {

    companion object {
        fun create(context: StressContext, profile: IStressProfile) : ProfileRunner {
            val prefix = context.mainArguments.id + "." + context.thread + "."
            val partitionKeyGenerator = PartitionKeyGenerator.random(prefix)
            return ProfileRunner(context, profile, partitionKeyGenerator, profile.getSampler(context.session, context.mainArguments.sampleRate))
        }
    }

    fun print(message: String) {
        println("[Thread ${context.thread}]: $message")

    }
    /**

     */
    fun run() {

        logger.info { "Starting up runner" }

        // need to add a warmup / populate phase

        executeOperations(context.mainArguments.iterations)

        print("All operations complete.  Validating.")
        // put a countdownlatch here, wait to validate
        validate()
    }

    /**
     * Used for both pre-populating data and for performing the actual runner
     */
    private fun executeOperations(iterations: Long) {
        // we're going to (for now) only keep 1000 in flight queries per session


        var operations = 0
        val sem = context.semaphore

        val runner = profile.getRunner(context.profileArguments)

        for (key in partitionKeyGenerator.generateKey(iterations, context.mainArguments.partitionValues)) {

            // get next thing from the profile
            // thing could be a statement, or it could be a failure command
            // certain profiles will want to deterministically inject failures
            // others can be randomly injected by the runner
            // I should be able to just tell the runner to inject gossip failures in any test
            // without having to write that code in the profile

            val op : Operation = if(context.mainArguments.readRate * 100 > ThreadLocalRandom.current().nextInt(0, 100)) {
                runner.getNextSelect(key)
            } else {
                runner.getNextMutation(key)
            }

            context.semaphore.acquire()

            // TODO: instead of using the context request & errors, pass them in
            // that way this can be reused for the pre-population
            when (op) {
                is Operation.Mutation -> {
                    logger.debug { op }


                    val future = context.session.executeAsync(op.bound)

                    Futures.addCallback(future, object : FutureCallback<ResultSet> {
                        override fun onFailure(t: Throwable?) {
                            context.semaphore.release()
                            context.metrics.errors.mark()
                            logger.error { t }
                        }

                        override fun onSuccess(result: ResultSet?) {
                            context.semaphore.release()
                            // if the key returned in the Mutation exists in the sampler, store the fields
                            // if not, use the sampler frequency
                            // need to be mindful of memory, frequency is a stopgap
                            sampler.maybePut(op.partitionKey, op.fields)
                            context.metrics.mutations.mark()

                        }
                    })
                }

                is Operation.SelectStatement -> {
                    val future = context.session.executeAsync(op.bound)
                    Futures.addCallback(future, object : FutureCallback<ResultSet> {
                        override fun onFailure(t: Throwable?) {
                            context.semaphore.release()
                            context.metrics.errors.mark()
                            logger.error { t }
                        }

                        override fun onSuccess(result: ResultSet?) {
                            context.semaphore.release()
                            // if the key returned in the Mutation exists in the sampler, store the fields
                            // if not, use the sampler frequency
                            // need to be mindful of memory, frequency is a stopgap
                            context.metrics.selects.mark()

                        }
                    })
                }
            }
            operations++
        }

        // block until all the queries are finished
        sem.acquireUninterruptibly(context.permits)

        print("Operations: $operations")

        // wait for outstanding operations to complete

    }

    /**
     * TODO return validation statistics
     */
    fun validate() {
        print("Verifying dataset ${sampler.size()} samples.")
        val stats = sampler.validate()
        print("Stats: $stats")

    }

    fun prepare() {
        profile.prepare(context.session)
        val prefix = context.mainArguments.id + "." + context.thread + "."
        val sequenceGenerator = PartitionKeyGenerator.sequence(prefix)

        // populate the DB with random values
        if(context.mainArguments.populate) {
            val sem = Semaphore(context.permits)
            val runner = profile.getRunner(context.profileArguments)

            var inserted = 0
            // for now, simply generate a single value for each partition key
            for(key in sequenceGenerator.generateKey(context.mainArguments.partitionValues.toLong())) {
                sem.acquire()
                val op = runner.getNextMutation(key)
                if(op is Operation.Mutation) {
                    val future = context.session.executeAsync(op.bound)
                    Futures.addCallback(future, object : FutureCallback<ResultSet> {
                        override fun onFailure(t: Throwable?) {
                            context.semaphore.release()
                            inserted++
                            logger.error { t }
                        }

                        override fun onSuccess(result: ResultSet?) {
                            context.semaphore.release()
                        }
                    })

                }
            }
            sem.acquireUninterruptibly(context.permits)
            println("pre-populated: $inserted inserts")
        }
    }


}