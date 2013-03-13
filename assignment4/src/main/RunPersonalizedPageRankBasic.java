/*
 * Cloud9: A Hadoop toolkit for working with big data
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import com.google.common.base.Preconditions;

import edu.umd.cloud9.example.pagerank.RunPageRankSchimmy;
import edu.umd.cloud9.io.array.ArrayListOfFloatsWritable;
import edu.umd.cloud9.io.array.ArrayListOfIntsWritable;
import edu.umd.cloud9.mapreduce.lib.input.NonSplitableSequenceFileInputFormat;

/**
 * <p>
 * Main driver program for running the basic (non-Schimmy) implementation of
 * PageRank.
 * </p>
 *
 * <p>
 * The starting and ending iterations will correspond to paths
 * <code>/base/path/iterXXXX</code> and <code>/base/path/iterYYYY</code>. As a
 * example, if you specify 0 and 10 as the starting and ending iterations, the
 * driver program will start with the graph structure stored at
 * <code>/base/path/iter0000</code>; final results will be stored at
 * <code>/base/path/iter0010</code>.
 * </p>
 *
 * @see RunPageRankSchimmy
 * @author Jimmy Lin
 * @author Michael Schatz
 */
public class RunPersonalizedPageRankBasic extends Configured implements Tool {
	private static final Logger LOG = Logger.getLogger(RunPersonalizedPageRankBasic.class);

	private static enum PageRank {
		nodes, edges, massMessages, massMessagesSaved, massMessagesReceived, missingStructure
	};

	// Mapper, no in-mapper combining.
	private static class MapClass extends
	Mapper<IntWritable, PageRankNodeExtended, IntWritable, PageRankNodeExtended> {

		// The neighbor to which we're sending messages.
		private static final IntWritable neighbor = new IntWritable();

		// Contents of the messages: partial PageRank mass.
		private static final PageRankNodeExtended intermediateMass = new PageRankNodeExtended();

		// For passing along node structure.
		private static final PageRankNodeExtended intermediateStructure = new PageRankNodeExtended();

		private static HashMap<Integer, Integer> sourceIdToPosition = new HashMap<Integer, Integer>();

		@Override
		public void setup(Context context){

			String[] sourceList = context.getConfiguration().getStrings("sources");
			if(sourceList.length == 0){
				throw new RuntimeException("Source list cannot be empty!");
			}

			for(int i = 0; i < sourceList.length; i++){
				sourceIdToPosition.put(Integer.parseInt(sourceList[i]), i);
			}
			
		}

		@Override
		public void map(IntWritable nid, PageRankNodeExtended node, Context context)
				throws IOException, InterruptedException {
			// Pass along node structure.
			intermediateStructure.setNodeId(node.getNodeId());
			intermediateStructure.setType(PageRankNodeExtended.Type.Structure);
			intermediateStructure.setAdjacencyList(node.getAdjacenyList());

			context.write(nid, intermediateStructure);

			int massMessages = 0;

			// Distribute PageRank mass to neighbors (along outgoing edges).

			if (node.getAdjacenyList().size() > 0) {
				// Each neighbor gets an equal share of PageRank mass.
				ArrayListOfIntsWritable list = node.getAdjacenyList();

				float[] masses = node.getPageRankArray().getArray();
				for(int i = 0; i < masses.length; i++){
					masses[i] = masses[i] - (float) StrictMath.log(list.size());
				}


				//				float mass = node.getPageRank() - (float) StrictMath.log(list.size());
				//				LOG.info( node.getNodeId() + " distributing " + node.getPageRank() + " total over " + list.size() + " neighbors. Each: " + mass + " pagerank.");

				context.getCounter(PageRank.edges).increment(list.size());

				// Iterate over neighbors.
				String neighborsString = "Neighbors of " + node.getNodeId(); 
				for (int i = 0; i < list.size(); i++) {
					neighborsString += "\t" + list.get(i) + "\t";

					neighbor.set(list.get(i));
					intermediateMass.setNodeId(list.get(i));
					intermediateMass.setType(PageRankNodeExtended.Type.Mass);

					//TODO This might be inefficient to create a new object here.
					intermediateMass.setPageRankArray(new ArrayListOfFloatsWritable(masses));

					// Emit messages with PageRank mass to neighbors.
					context.write(neighbor, intermediateMass);
					massMessages++;
				}

				LOG.info(neighborsString);

			}

			// Bookkeeping.
			context.getCounter(PageRank.nodes).increment(1);
			context.getCounter(PageRank.massMessages).increment(massMessages);
		}
	}


	// Combiner: sums partial PageRank contributions and passes node structure along.
	//	private static class CombineClass extends
	//	Reducer<IntWritable, PageRankNodeExtended, IntWritable, PageRankNodeExtended> {
	//		private static final PageRankNodeExtended intermediateMass = new PageRankNodeExtended();
	//
	//		@Override
	//		public void reduce(IntWritable nid, Iterable<PageRankNodeExtended> values, Context context)
	//				throws IOException, InterruptedException {
	//			int massMessages = 0;
	//
	//			// Remember, PageRank mass is stored as a log prob.
	//			float mass = Float.NEGATIVE_INFINITY;
	//			for (PageRankNodeExtended n : values) {
	//				if (n.getType() == PageRankNodeExtended.Type.Structure) {
	//					// Simply pass along node structure.
	//					context.write(nid, n);
	//				} else {
	//					// Accumulate PageRank mass contributions.
	//					mass = sumLogProbs(mass, n.getPageRank());
	//					massMessages++;
	//				}
	//			}
	//
	//			// Emit aggregated results.
	//			if (massMessages > 0) {
	//				intermediateMass.setNodeId(nid.get());
	//				intermediateMass.setType(PageRankNodeExtended.Type.Mass);
	//				intermediateMass.setPageRank(mass);
	//
	//				context.write(nid, intermediateMass);
	//			}
	//		}
	//	}

	// Reduce: sums incoming PageRank contributions, rewrite graph structure.
	private static class ReduceClass extends
	Reducer<IntWritable, PageRankNodeExtended, IntWritable, PageRankNodeExtended> {
		// For keeping track of PageRank mass encountered, so we can compute missing PageRank mass lost
		// through dangling nodes.
		private float totalMass = Float.NEGATIVE_INFINITY;
		private static int numSources;
		
		@Override
		public void setup(Context context){
			String[] sourceList = context.getConfiguration().getStrings("sources");
			numSources = sourceList.length;
		}

		@Override
		public void reduce(IntWritable nid, Iterable<PageRankNodeExtended> iterable, Context context)
				throws IOException, InterruptedException {
			Iterator<PageRankNodeExtended> values = iterable.iterator();

			// Create the node structure that we're going to assemble back together from shuffled pieces.
			PageRankNodeExtended node = new PageRankNodeExtended();

			node.setType(PageRankNodeExtended.Type.Complete);
			node.setNodeId(nid.get());

			int massMessagesReceived = 0;
			int structureReceived = 0;

			float[] masses = new float[numSources];
			for(int i = 0; i < masses.length; i++){
				masses[i] = Float.NEGATIVE_INFINITY;
			}
			
			while (values.hasNext()) {
				PageRankNodeExtended n = values.next();
				LOG.info("Reconstructing node: " + node.getNodeId());


				if (n.getType().equals(PageRankNodeExtended.Type.Structure)) {
					LOG.info(" - Got a structure node");
					// This is the structure; update accordingly.
					ArrayListOfIntsWritable list = n.getAdjacenyList();
					structureReceived++;

					node.setAdjacencyList(list);
				} else {
					// This is a message that contains PageRank mass; accumulate.
					LOG.info(" - Got an intermediate mass node");
//					LOG.info(" - Accumulating mass: " + n.getPageRank());
					
					
					ArrayListOfFloatsWritable pageranks = n.getPageRankArray();
					for(int i = 0; i < masses.length; i++){
						masses[i] = sumLogProbs(masses[i], pageranks.get(i));
					}
					
//					mass = sumLogProbs(mass, n.getPageRank());
					massMessagesReceived++;
				}
			}

			// Update the final accumulated PageRank mass.
			LOG.info("Done accumulating mass: " + masses);
			
			//TODO Maybe inefficient here
			node.setPageRankArray(new ArrayListOfFloatsWritable(masses));
			context.getCounter(PageRank.massMessagesReceived).increment(massMessagesReceived);

			// Error checking.
			if (structureReceived == 1) {
				// Everything checks out, emit final node structure with updated PageRank value.
				context.write(nid, node);

				// Keep track of total PageRank mass.
				totalMass = sumLogProbs(totalMass, masses[0]);
			} else if (structureReceived == 0) {
				// We get into this situation if there exists an edge pointing to a node which has no
				// corresponding node structure (i.e., PageRank mass was passed to a non-existent node)...
				// log and count but move on.
				context.getCounter(PageRank.missingStructure).increment(1);
				LOG.warn("No structure received for nodeid: " + nid.get() + " mass: "
						+ massMessagesReceived);
				// It's important to note that we don't add the PageRank mass to total... if PageRank mass
				// was sent to a non-existent node, it should simply vanish.
			} else {
				// This shouldn't happen!
				throw new RuntimeException("Multiple structure received for nodeid: " + nid.get()
						+ " mass: " + massMessagesReceived + " struct: " + structureReceived);
			}
		}

		@Override
		public void cleanup(Context context) throws IOException {
			Configuration conf = context.getConfiguration();
			String taskId = conf.get("mapred.task.id");
			String path = conf.get("PageRankMassPath");

			Preconditions.checkNotNull(taskId);
			Preconditions.checkNotNull(path);

			// Write to a file the amount of PageRank mass we've seen in this reducer.
			FileSystem fs = FileSystem.get(context.getConfiguration());
			FSDataOutputStream out = fs.create(new Path(path + "/" + taskId), false);
			out.writeFloat(totalMass);
			out.close();
		}
	}

	// Mapper that distributes the missing PageRank mass (lost at the dangling nodes) and takes care
	// of the random jump factor.
	private static class MapPageRankMassDistributionClass extends
	Mapper<IntWritable, PageRankNodeExtended, IntWritable, PageRankNodeExtended> {
		private float missingMass = 0.0f;
		private static HashMap<Integer, Integer> sourceIdToPosition = new HashMap<Integer, Integer>();

		@Override
		public void setup(Context context) throws IOException {
			Configuration conf = context.getConfiguration();

			missingMass = conf.getFloat("MissingMass", 0.0f);			
			LOG.info("MissingMass (normal scale): " + missingMass);


			String[] sourceList = context.getConfiguration().getStrings("sources");
			if(sourceList.length == 0){
				throw new RuntimeException("Source list cannot be empty!");
			}

			for(int i = 0; i < sourceList.length; i++){
				sourceIdToPosition.put(Integer.parseInt(sourceList[i]), i);
			}


		}

		@Override
		public void map(IntWritable nid, PageRankNodeExtended node, Context context)
				throws IOException, InterruptedException {

			//If this is a source node, give it the missing mass and the jump mass
			//Otherwise, nothing.
			ArrayListOfFloatsWritable pageranks = node.getPageRankArray();
			for(int i = 0; i < sourceIdToPosition.size(); i++){
				
				//Update each of the pagerank positions in node
				float jump = Float.NEGATIVE_INFINITY;
				float link = Float.NEGATIVE_INFINITY;
				
				if(sourceIdToPosition.containsKey(nid.get())){
					int sourcePosition = sourceIdToPosition.get(nid.get());
					
					if(sourcePosition == i){
						jump = (float) Math.log(ALPHA);
						link = (float) Math.log(1.0f - ALPHA)
								+ sumLogProbs(pageranks.get(i), (float) Math.log(missingMass));
					} else {
						link = (float) Math.log(1.0f - ALPHA) + pageranks.get(i);
					}
					
				} else {
					link = (float) Math.log(1.0f - ALPHA) + pageranks.get(i);
				}
				
				pageranks.set(i, sumLogProbs(jump, link));
				
			}
			context.write(nid, node);

			
			

//			if(sourceIdToPosition.containsKey(nid.get())){
//				int position = sourceIdToPosition.get(nid.get());
//
//				LOG.info("Adding missing mass to source : " + nid.get() + " in position: " + position);
//
//				ArrayListOfFloatsWritable p = node.getPageRankArray();
//
//				float jump = (float) Math.log(ALPHA);
//				float link = (float) Math.log(1.0f - ALPHA)
//						+ sumLogProbs(p.get(position), (float) Math.log(missingMass));
//
//				p.set(position, sumLogProbs(jump, link));
//				node.setPageRankArray(p);
//
//
//			} else {
//				float link = (float) Math.log(1.0f - ALPHA)
//						+ sumLogProbs(p.get(position), (float) Math.log(missingMass));
//			}
//
//			context.write(nid, node);
		}
	}

	// Random jump factor.
	private static float ALPHA = 0.15f;
	private static NumberFormat formatter = new DecimalFormat("0000");

	/**
	 * Dispatches command-line arguments to the tool via the {@code ToolRunner}.
	 */
	public static void main(String[] args) throws Exception {
		ToolRunner.run(new RunPersonalizedPageRankBasic(), args);
	}

	public RunPersonalizedPageRankBasic() {}

	private static final String BASE = "base";
	private static final String NUM_NODES = "numNodes";
	private static final String START = "start";
	private static final String END = "end";
	private static final String COMBINER = "useCombiner";
	private static final String RANGE = "range";
	private static final String SOURCES = "sources";

	/**
	 * Runs this tool.
	 */
	@SuppressWarnings({ "static-access" })
	public int run(String[] args) throws Exception {
		Options options = new Options();

		options.addOption(new Option(COMBINER, "use combiner"));
		options.addOption(new Option(RANGE, "use range partitioner"));

		options.addOption(OptionBuilder.withArgName("path").hasArg()
				.withDescription("base path").create(BASE));
		options.addOption(OptionBuilder.withArgName("num").hasArg()
				.withDescription("start iteration").create(START));
		options.addOption(OptionBuilder.withArgName("num").hasArg()
				.withDescription("end iteration").create(END));
		options.addOption(OptionBuilder.withArgName("num").hasArg()
				.withDescription("number of nodes").create(NUM_NODES));
		options.addOption(OptionBuilder.withArgName("sources").hasArg()
				.withDescription("source nodes").create(SOURCES));

		CommandLine cmdline;
		CommandLineParser parser = new GnuParser();

		try {
			cmdline = parser.parse(options, args);
		} catch (ParseException exp) {
			System.err.println("Error parsing command line: " + exp.getMessage());
			return -1;
		}

		if (!cmdline.hasOption(BASE) || !cmdline.hasOption(START)
				|| !cmdline.hasOption(END)
				|| !cmdline.hasOption(NUM_NODES)
				|| !cmdline.hasOption(SOURCES)) {
			System.out.println("args: " + Arrays.toString(args));
			HelpFormatter formatter = new HelpFormatter();
			formatter.setWidth(120);
			formatter.printHelp(this.getClass().getName(), options);
			ToolRunner.printGenericCommandUsage(System.out);
			return -1;
		}

		String basePath = cmdline.getOptionValue(BASE);
		int n = Integer.parseInt(cmdline.getOptionValue(NUM_NODES));
		int s = Integer.parseInt(cmdline.getOptionValue(START));
		int e = Integer.parseInt(cmdline.getOptionValue(END));
		boolean useCombiner = cmdline.hasOption(COMBINER);
		boolean useRange = cmdline.hasOption(RANGE);
		String sourcesString = cmdline.getOptionValue(SOURCES);


		LOG.info("Tool name: RunPageRank");
		LOG.info(" - base path: " + basePath);
		LOG.info(" - num nodes: " + n);
		LOG.info(" - start iteration: " + s);
		LOG.info(" - end iteration: " + e);
		LOG.info(" - use combiner: " + useCombiner);
		LOG.info(" - user range partitioner: " + useRange);
		LOG.info(" - sources: " + sourcesString);

		// Iterate PageRank.
		for (int i = s; i < e; i++) {
			iteratePageRank(i, i + 1, basePath, n, useCombiner, sourcesString);
		}

		return 0;
	}

	// Run each iteration.
	private void iteratePageRank(int i, int j, String basePath, int numNodes,
			boolean useCombiner, String sources) throws Exception {
		// Each iteration consists of two phases (two MapReduce jobs).

		// Job 1: distribute PageRank mass along outgoing edges.
		float mass = phase1(i, j, basePath, numNodes, useCombiner, sources);

		// Find out how much PageRank mass got lost at the dangling nodes.
		float missing = 1.0f - (float) StrictMath.exp(mass);

		// Job 2: distribute missing mass, take care of random jump factor.
		phase2(i, j, missing, basePath, numNodes, sources);
	}

	private float phase1(int i, int j, String basePath, int numNodes,
			boolean useCombiner, String sources) throws Exception {
		Job job = Job.getInstance(getConf());
		job.setJobName("PageRank:Basic:iteration" + j + ":Phase1");
		job.setJarByClass(RunPersonalizedPageRankBasic.class);
		job.getConfiguration().setStrings("sources", sources);


		String in = basePath + "/iter" + formatter.format(i);
		String out = basePath + "/iter" + formatter.format(j) + "t";
		String outm = out + "-mass";

		// We need to actually count the number of part files to get the number of partitions (because
		// the directory might contain _log).
		int numPartitions = 0;
		for (FileStatus s : FileSystem.get(getConf()).listStatus(new Path(in))) {
			if (s.getPath().getName().contains("part-"))
				numPartitions++;
		}

		LOG.info("PageRank: iteration " + j + ": Phase1");
		LOG.info(" - input: " + in);
		LOG.info(" - output: " + out);
		LOG.info(" - nodeCnt: " + numNodes);
		LOG.info(" - useCombiner: " + useCombiner);
		LOG.info("computed number of partitions: " + numPartitions);

		int numReduceTasks = numPartitions;

		job.getConfiguration().setInt("NodeCount", numNodes);
		job.getConfiguration().setBoolean("mapred.map.tasks.speculative.execution", false);
		job.getConfiguration().setBoolean("mapred.reduce.tasks.speculative.execution", false);
		//job.getConfiguration().set("mapred.child.java.opts", "-Xmx2048m");
		job.getConfiguration().set("PageRankMassPath", outm);

		job.setNumReduceTasks(numReduceTasks);

		FileInputFormat.setInputPaths(job, new Path(in));
		FileOutputFormat.setOutputPath(job, new Path(out));

		job.setInputFormatClass(NonSplitableSequenceFileInputFormat.class);
		job.setOutputFormatClass(SequenceFileOutputFormat.class);

		job.setMapOutputKeyClass(IntWritable.class);
		job.setMapOutputValueClass(PageRankNodeExtended.class);

		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(PageRankNodeExtended.class);

		job.setMapperClass(MapClass.class);

		if (useCombiner) {
			//			job.setCombinerClass(CombineClass.class);
		}

		job.setReducerClass(ReduceClass.class);

		FileSystem.get(getConf()).delete(new Path(out), true);
		FileSystem.get(getConf()).delete(new Path(outm), true);

		long startTime = System.currentTimeMillis();
		job.waitForCompletion(true);
		System.out.println("Job Finished in " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

		float mass = Float.NEGATIVE_INFINITY;
		FileSystem fs = FileSystem.get(getConf());
		for (FileStatus f : fs.listStatus(new Path(outm))) {
			FSDataInputStream fin = fs.open(f.getPath());
			mass = sumLogProbs(mass, fin.readFloat());
			fin.close();
		}

		return mass;
	}

	private void phase2(int i, int j, float missing, String basePath, int numNodes, String sources) throws Exception {
		Job job = Job.getInstance(getConf());
		job.setJobName("PageRank:Basic:iteration" + j + ":Phase2");
		job.setJarByClass(RunPersonalizedPageRankBasic.class);
		job.getConfiguration().setStrings("sources", sources);


		LOG.info("missing PageRank mass: " + missing);
		LOG.info("number of nodes: " + numNodes);

		String in = basePath + "/iter" + formatter.format(j) + "t";
		String out = basePath + "/iter" + formatter.format(j);

		LOG.info("PageRank: iteration " + j + ": Phase2");
		LOG.info(" - input: " + in);
		LOG.info(" - output: " + out);

		job.getConfiguration().setBoolean("mapred.map.tasks.speculative.execution", false);
		job.getConfiguration().setBoolean("mapred.reduce.tasks.speculative.execution", false);
		job.getConfiguration().setFloat("MissingMass", (float) missing);
		job.getConfiguration().setInt("NodeCount", numNodes);

		job.setNumReduceTasks(0);

		FileInputFormat.setInputPaths(job, new Path(in));
		FileOutputFormat.setOutputPath(job, new Path(out));

		job.setInputFormatClass(NonSplitableSequenceFileInputFormat.class);
		job.setOutputFormatClass(SequenceFileOutputFormat.class);

		job.setMapOutputKeyClass(IntWritable.class);
		job.setMapOutputValueClass(PageRankNodeExtended.class);

		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(PageRankNodeExtended.class);

		job.setMapperClass(MapPageRankMassDistributionClass.class);

		FileSystem.get(getConf()).delete(new Path(out), true);

		long startTime = System.currentTimeMillis();
		job.waitForCompletion(true);
		System.out.println("Job Finished in " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
	}

	// Adds two log probs.
	private static float sumLogProbs(float a, float b) {
		if (a == Float.NEGATIVE_INFINITY)
			return b;

		if (b == Float.NEGATIVE_INFINITY)
			return a;

		if (a < b) {
			return (float) (b + StrictMath.log1p(StrictMath.exp(a - b)));
		}

		return (float) (a + StrictMath.log1p(StrictMath.exp(b - a)));
	}
}