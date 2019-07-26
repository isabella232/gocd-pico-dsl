package com.github.masooh.gocdpicodsl.dsl

import org.jgrapht.Graph
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.SimpleDirectedGraph
import java.util.*

@DslMarker annotation class PipelineGroupMarker
@DslMarker annotation class PipelineMarker
@DslMarker annotation class ContextMarker

@ContextMarker
class Context {
    val data = mutableMapOf<String, String>()
    val postProcessors: MutableList<(PipelineSingle) -> Unit> = mutableListOf()

    fun forAll(enhancePipeline: PipelineSingle.() -> Unit) {
        postProcessors.add(enhancePipeline)
    }
}

object ContextStack {
    val context : Deque<Context> = LinkedList()
    val current: Context?
            get() = context.peekLast()
}

sealed class PipelineGroup {
    val pipelinesInGroup = mutableListOf<PipelineGroup>()
    val graphProcessors = mutableListOf<PipelineSingle.(Graph<PipelineSingle, DefaultEdge>)-> Unit>()

    abstract fun getEndingPipelines(): List<PipelineSingle>
    abstract fun getStartingPipelines(): List<PipelineSingle>

    open fun getAllPipelines(): List<PipelineSingle> = pipelinesInGroup.flatMap { it.getAllPipelines() }

    abstract fun addPipelineGroupToGraph(graph: Graph<PipelineSingle, DefaultEdge>)

    open fun runGraphProcessors(graph: Graph<PipelineSingle, DefaultEdge>) {
        pipelinesInGroup.forEach { it.runGraphProcessors(graph)}
    }

    fun pipeline(name: String, block: PipelineSingle.() -> Unit): PipelineSingle {
        val pipelineSingle = PipelineSingle(name)
        pipelineSingle.block()

        pipelinesInGroup.add(pipelineSingle)

        return pipelineSingle
    }

    fun group(groupName: String, block: Context.() -> Unit): Context {
        return context({
            postProcessors.add { pipeline ->
                if (pipeline.group == null) {
                    pipeline.group = groupName
                }
            }
        }, block)
    }

    fun context(init: Context.() -> Unit = {}, block: Context.() -> Unit): Context {
        val context = Context()
        ContextStack.context.add(context)

        context.init()
        context.block()

        getAllPipelines().forEach { pipeline ->
            context.postProcessors.forEach { processor ->
                pipeline.apply(processor)
            }
        }
        ContextStack.context.removeLast()
        return context
    }
}

@PipelineGroupMarker
class GocdConfig {
    val graph: Graph<PipelineSingle, DefaultEdge> = SimpleDirectedGraph(DefaultEdge::class.java)

    val pipelines : MutableList<PipelineGroup> = mutableListOf()

    fun environments() {}

    fun sequence(init: PipelineSequence.() -> Unit): PipelineSequence {
        val pipelineSequence = PipelineSequence()
        pipelineSequence.init()
        pipelineSequence.addPipelineGroupToGraph(graph)

        pipelines.add(pipelineSequence)
        return pipelineSequence
    }

    fun parallel(init: PipelineParallel.() -> Unit): PipelineParallel {
        val pipelineParallel = PipelineParallel(null)
        pipelineParallel.init()
        pipelineParallel.addPipelineGroupToGraph(graph)

        pipelines.add(pipelineParallel)
        return pipelineParallel
    }

    fun finalize(): GocdConfig {
        pipelines.forEach {
            it.runGraphProcessors(graph)
        }
        return this
    }
}

fun gocd(init: GocdConfig.() -> Unit) = GocdConfig().apply(init).finalize()

@PipelineGroupMarker
class PipelineSequence : PipelineGroup() {
    override fun addPipelineGroupToGraph(graph: Graph<PipelineSingle, DefaultEdge>) {
        pipelinesInGroup.forEach { it.addPipelineGroupToGraph(graph) }

        var fromGroup = pipelinesInGroup.first()

        pipelinesInGroup.drop(1).forEach { toGroup ->
                fromGroup.getEndingPipelines().forEach { from ->
                    toGroup.getStartingPipelines().forEach { to ->
                        graph.addEdge(from, to)
                    }
                }
                fromGroup = toGroup
        }
    }

    override fun getStartingPipelines(): List<PipelineSingle> = pipelinesInGroup.first().getStartingPipelines()
    override fun getEndingPipelines(): List<PipelineSingle> = pipelinesInGroup.last().getEndingPipelines()

    fun parallel(init: PipelineParallel.() -> Unit): PipelineParallel {
        val lastPipeline = if (pipelinesInGroup.isNotEmpty()) pipelinesInGroup.last() as PipelineSingle else null
        val pipelineParallel = PipelineParallel(lastPipeline)
        pipelineParallel.init()

        pipelinesInGroup.add(pipelineParallel)

        return pipelineParallel
    }
}

@PipelineGroupMarker
class PipelineParallel(private val forkPipeline: PipelineSingle?) : PipelineGroup() {
    override fun addPipelineGroupToGraph(graph: Graph<PipelineSingle, DefaultEdge>) {
        pipelinesInGroup.forEach {it.addPipelineGroupToGraph(graph)}
        if (forkPipeline != null) {
            pipelinesInGroup.forEach { toGroup ->
                forkPipeline.getEndingPipelines().forEach { from ->
                    toGroup.getStartingPipelines().forEach { to ->
                        graph.addEdge(from, to)
                    }
                }
            }
        }
    }

    override fun getStartingPipelines() = pipelinesInGroup.flatMap { it.getStartingPipelines() }
    override fun getEndingPipelines() = pipelinesInGroup.flatMap { it.getEndingPipelines() }

    fun sequence(init: PipelineSequence.() -> Unit): PipelineSequence {
        val pipelineSequence = PipelineSequence()
        pipelineSequence.init()

        this.pipelinesInGroup.add(pipelineSequence)
        return pipelineSequence
    }
}

@PipelineMarker
// todo darf nicht von Group erben, da es selbst wieder pipeline() anbietet
data class PipelineSingle(val name: String) : PipelineGroup() {
    val tags = mutableMapOf<String, String>()

    fun tag(key: String, value: String) {
        tags[key] = value
    }

    var lockBehavior: LockBehavior = LockBehavior.unlockWhenFinished

    val lastStage: String
        get() {
            return template?.stage ?: stages.last().name
        }

    var parameters = mutableMapOf<String, String>()
    var environmentVariables = mutableMapOf<String, String>()

    var template : Template? = null
    var group : String? = null

    var stages : MutableList<Stage> = mutableListOf()
    var materials: Materials? = null

    override fun addPipelineGroupToGraph(graph: Graph<PipelineSingle, DefaultEdge>) {
        graph.addVertex(this)
    }

    override fun runGraphProcessors(graph: Graph<PipelineSingle, DefaultEdge>) {
        graphProcessors.forEach { processor ->
            this.apply { processor(graph) }
        }
    }

    override fun getStartingPipelines() = listOf(this)
    override fun getEndingPipelines() = listOf(this)
    override fun getAllPipelines() = listOf(this)

    fun parameter(key: String, value: String) {
        parameters[key] = value
    }

    fun environment(key: String, value: String) {
        environmentVariables[key] = value
    }

    fun stage(name: String, manualApproval: Boolean = false, init: Stage.() -> Unit): Stage {
        val stage = Stage(name, manualApproval)
        stage.init()
        stages.add(stage)
        return stage
    }

    fun materials(init: Materials.() -> Unit): Materials {
        val materials = Materials()
        materials.init()
        this.materials = materials
        return materials
    }

    fun environment(key: String, value: Any) {
        environmentVariables[key] = value.toString()
    }
}

fun pathToPipeline(graph: Graph<PipelineSingle, DefaultEdge>, to: PipelineSingle, matcher: (PipelineSingle) -> Boolean): String {

    val candidates = graph.vertexSet().filter(matcher)
    val dijkstraAlg = DijkstraShortestPath(graph)

    val shortestPath = candidates.map { candidate ->
        val startPath = dijkstraAlg.getPaths(candidate)
        startPath.getPath(to).edgeList
    }.minBy { it.size } ?: throw IllegalArgumentException("not path found to $to")

    return shortestPath.joinToString(separator = "/") { edge ->
        graph.getEdgeSource(edge).name
    }
}