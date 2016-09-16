/*
 * Copyright (C) 2016 by
 * 
 * 	Christoph Carl Kling
 *	pcfst ät c-kling.de
 *  Institute for Web Science and Technologies (WeST)
 *  University of Koblenz-Landau
 *  west.uni-koblenz.de
 *
 * HMDP is a free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 *
 * HMDP is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCFST; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.
 */

package org.gesis.promoss.inference;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.gesis.promoss.tools.math.BasicMath;
import org.gesis.promoss.tools.probabilistic.DirichletEstimation;
import org.gesis.promoss.tools.probabilistic.Pair;
import org.gesis.promoss.tools.text.DMR_Corpus;
import org.gesis.promoss.tools.text.Save;

import cc.mallet.optimize.LimitedMemoryBFGS;
import cc.mallet.optimize.OptimizationException;


/**
 * This is the practical collapsed stochastic variational inference
 * for the Hierarchical Multi-Dirichlet Process Topic Model (HMDP)
 */
public class DMR_CSVB {

	//This class holds the corpus and its properties
	//including metadata
	public DMR_Corpus c;
	
	//We have a debugging mode for checking the parameters
	public boolean debug = false;
	//number of top words returned for the topic file
	public int topk = 100;
	//Number of read docs (might repeat with the same docs)
	public int RUNS = 100;
	//Save variables after step SAVE_STEP
	public int SAVE_STEP = 10;
	public int BATCHSIZE = 128;
	//How many observations do we take before updating alpha
	public int BATCHSIZE_ALPHA = 1000;
	//After how many steps a sample is taken to estimate alpha
	public int SAMPLE_ALPHA = 1;
	//Burn in phase: how long to wait till updating nkt?
	public int BURNIN = 0;
	//Burn in phase for documents: How long till we update the
	//parameters of the regression. As in Mallet: 20
	public int BURNIN_DOCUMENTS = 20;
	//How often to train the DMR?
	public int optimizeInterval = 20;
	//should the topics be randomly initialised?
	public double INIT_RAND = 1;

	//relative size of the training set
	public double TRAINING_SHARE = 1.0;

	public String save_prefix = "";

	public int T = 100; //Number of topics


	public double[] alpha;

	//Dirichlet concentration parameter for topic-word distributions
	public double beta = 0.01;

	//helping variable beta*V
	private double beta_V;

	//Store some zeros for empty documents in the doc_topic matrix?
	public Boolean store_empty = true;

	//Estimated number of times term t appeared in topic k
	public double[][] nkt;
	//Estimated number of times term t appeared in topic k in the batch
	private double[][] tempnkt;
	//Estimated number of words in topic k
	public double[] nk;

	//Topic "counts" per document
	public double[][] nmk;


	//rho: Learning rate; rho = s / ((tau + t)^kappa);
	//recommended values: See "Online learning for latent dirichlet allocation" paper by Hoffman
	//tau = 64, K = 0.5; S = 1; Batchsize = 4096

	public int rhos = 1;
	public double rhokappa = 0.5;
	public int rhotau = 64;

	//public int rhos = 1;
	//public double rhokappa = 0.5;
	//public int rhotau = 2000;

	public int rhos_document = 1;
	public double rhokappa_document = 0.5;
	public int rhotau_document = 64;

	//tells the number of processed words
	public int rhot = 0;
	//tells the number of the current run)
	public int rhot_step = 0;
	//tells the number of words seen in this document
	private int[] rhot_words_doc;

	//count number of words seen in the batch
	//remember that rhot counts the number of documents, not words
	private int[] batch_words;

	/*
	 * Here we define helper variables
	 * Every feature has clusters
	 * Clusters belong to groups of connected clusters (e.g. adjacent clusters).
	 */

	
	public double rhostkt_document;
	public double oneminusrhostkt_document;

	//counts, how many documents we observed in the batch to estimate alpha
	public int alpha_batch_counter = 0;


	
    int numFeatures;
    int defaultFeatureIndex;

	
	double[][] alphaCache;
    double[] alphaSumCache;
    
	protected double alphaSum;
	//The regression class
	DMR dmr = null;
	LimitedMemoryBFGS optimizer = null;

	
	DMR_CSVB() {
		c = new DMR_Corpus();
	}
	
	public void initialise () {
		
		// Folder names, files etc. 
		c.dictfile = c.directory+"words.txt";
		//textfile contains the group-IDs for each feature dimension of the document
		//and the words of the document, all seperated by space (example line: a1,a2,a3,...,aF word1 word2 word3 ... wordNm)
		c.documentfile = c.directory+"corpus.txt";
		//metadata file, contains metadata separated by commas
		c.metafile = c.directory+"meta.txt";
		
		System.out.println("Creating dictionary...");
		c.readDict();	
		System.out.println("Initialising parameters...");
		initParameters();
		System.out.println("Processing documents...");
		c.readDocs();
		System.out.println("Estimating topics...");
		
		
	}

	public void run () {
		for (int i=0;i<RUNS;i++) {

			System.out.println(c.directory + " run " + i + " (alpha "+ BasicMath.sum(alpha)/T+ " beta " + beta);

			rhot_step++;
			//get step size
			rhostkt_document = rho(rhos_document,rhotau_document,rhokappa_document,rhot_step);
			oneminusrhostkt_document = (1.0 - rhostkt_document);

			int progress = c.M / 50;
			if (progress==0) progress = 1;
			for (int m=0;m<Double.valueOf(c.M)*TRAINING_SHARE;m++) {
				if(m%progress == 0) {
					System.out.print(".");
				}

				inferenceDoc(m);
			}
			System.out.println();

			updateHyperParameters();
			
			if (rhot > BURNIN_DOCUMENTS &&  rhot % optimizeInterval == 0) {
			//Here we train the Dirichlet-Multinomial Regression using original Mallet code
			if (dmr == null) {
				dmr = new DMR(c.meta, nmk);
				optimizer = new LimitedMemoryBFGS(dmr);				
			}
			else {
				//update observations
				dmr.observations = nmk;
			}


			// Optimize once
			try {
				optimizer.optimize();
			} catch (OptimizationException e) {
				// step size too small
			}
			
			// Optimize once
			try {
				optimizer.optimize();
			} catch (OptimizationException e) {
				// step size too small
			}
			
			double[] params = new double[dmr.getNumParameters()];
			dmr.getParameters(params);
			
			int K = dmr.K;
			int F = dmr.F;
			for (int k=0;k<K;k++) {
				for (int f=0;f<F;f++) {
					System.out.println ("K: " + k + " F: "+f + "  " + params[k*F+f]);
				}
			}
			}
		

			if (rhot_step%SAVE_STEP==0 || rhot_step == RUNS) {
				//store inferred variables
				System.out.println("Storing variables...");
				save();
			}
			
			if (rhot > BURNIN_DOCUMENTS){
			Save save = new Save();
			save.saveVar(perplexity()+"\n", "/home/c/dmr"+save_prefix+"perplexity");
			}

		}
	}



	//set Parameters
	public void initParameters() {
		
		beta_V = beta * c.V;
		

		if (rhos_document < 0) 
			rhos_document = rhos;
		
		if (rhotau_document < 0) 
			rhotau_document = rhotau;
		

		if (rhokappa_document < 0) 
			rhokappa_document = rhokappa;
				
		c.readFfromTextfile();

		c.V = c.dict.length();

		alpha = new double[T];
		for (int k=0;k<T;k++) {
			alpha[k] = 5.0 / T;
		}
		
		beta_V = beta * c.V;

		batch_words = new int[c.V];

		nk = new double[T];
		nkt = new double[T][c.V];	
		tempnkt = new double[T][c.V];	

		//read corpus size and initialise nkt / nk
		c.readCorpusSize();

		rhot_words_doc=new int[c.M];
	
		nmk = new double[c.M][T];

		for (int t=0; t < c.V; t++) {
			for (int k=0;k<T;k++) {

				nkt[k][t]= Math.random()*INIT_RAND;
				nk[k]+=nkt[k][t];

			}
		}
		
	}


	

	public void inferenceDoc(int m) {

		//increase counter of documents seen
		rhot++;

		//Expectation(number of tables)
		double[] sumqmk = new double[T];

		double rhostkt_documentNm = rhostkt_document * c.getN(m);
		
		double[] alpha_m = null;
		if (rhot_step > BURNIN_DOCUMENTS) {
			alpha_m = dmr.predict(m);
		}
		else {
			alpha_m = alpha;
		}

		int[] termIDs = c.getTermIDs(m);
		short[] termFreqs = c.getTermFreqs(m);
		
		//Process words of the document
		for (int i=0;i<termIDs.length;i++) {

			//term index
			int t = termIDs[i];
			//How often doas t appear in the document?
			int termfreq = termFreqs[i];

			//update number of words seen
			rhot_words_doc[m]+=termfreq;
			if (rhot_step>BURNIN) {
				//increase number of words seen in that batch
				batch_words[t]+=termfreq;
			}

			//topic probabilities - q(z)
			double[] q = new double[T];
			//sum for normalisation
			double qsum = 0.0;
			


			for (int k=0;k<T;k++) {

				q[k] = 	//probability of topic given feature & group
						(nmk[m][k] + alpha_m[k])
						//probability of topic given word w
						* (nkt[k][t] + beta) 
						/ (nk[k] + beta_V);

				qsum+=q[k];
				
			}


			//Normalise gamma (sum=1), update counts and probabilities
			for (int k=0;k<T;k++) {
				//normalise
				q[k]/=qsum;

				if ((Double.isInfinite(q[k]) || q[k]>1 || Double.isNaN(q[k]) || Double.isNaN(nmk[m][k]) ||  Double.isInfinite(nmk[m][k])) && !debug) {
					System.out.println("Error calculating gamma " +
							" second part: " + (nkt[k][t] + beta) / (nk[k] + beta_V) + 
							" m " + m+ " " + c.getN(m)+ " " + termfreq + " "+ Math.pow(oneminusrhostkt_document,termfreq) + 
							" sumqmk " + sumqmk[k] + 
							" qk " + q[k] + 
							" nmk " + nmk[m][k] + 
							" nkt " + nkt[k][t]+ 	
							" alpha1 " + alpha +
							" beta " + beta + 
							" betaV " + beta_V
							);

					debug = true;
					//Skip this file...
					break;
				}

				//add to batch counts
				if (rhot_step>BURNIN) {
					tempnkt[k][t]+=q[k]*termfreq;
				}

				//update probability of _not_ seeing k in the current document
				sumqmk[k]+=Math.log(1.0-q[k])*termfreq;

				//in case the document contains only this word, we do not use nmk
				if (c.getN(m) != termfreq) {

					//update document-feature-cluster-topic counts
					if (termfreq==1) {
						nmk[m][k] = oneminusrhostkt_document * nmk[m][k] + rhostkt_documentNm * q[k];
					}
					else {
						double temp = Math.pow(oneminusrhostkt_document,termfreq);
						nmk[m][k] = temp * nmk[m][k] + (1.0-temp) * c.getN(m) * q[k];
					}
					
					//if (m==0) System.out.println(nmk[m][k] );

				}

			}

		}
		//End of loop over document words


		//get probability for NOT seeing topic f to update delta
		//double[] tables_per_feature = new double[c.F];



		double[] topic_ge_0 = new double[T];
		for (int k=0;k<T;k++) {
			//Probability that we saw the given topic
			topic_ge_0[k] = (1.0 - Math.exp(sumqmk[k]));
		}

		//We update global topic-word counts in batches (mini-batches lead to local optima)
		//after a burn-in phase
		if (rhot%BATCHSIZE == 0 && rhot_step>BURNIN) {
			updateTopicWordCounts();
		}

	}

	/**
	 * Here we do stochastic updates of the document-topic counts
	 */
	public synchronized void updateTopicWordCounts() {



		double rhostkt = rho(rhos,rhotau,rhokappa,rhot/BATCHSIZE);
		double rhostktnormC = rhostkt * (c.C / Double.valueOf(BasicMath.sum(batch_words)));

		

		nk = new double[T];
		for (int k=0;k<T;k++) {
			for (int v=0;v<c.V;v++) {
				double oneminusrhostkt = (1.0 - rhostkt);

				nkt[k][v] *= oneminusrhostkt;

				//we estimate the topic counts as the average q (tempnkt consists of BATCHSIZE observations)
				//and multiply this with the size of the corpus C
				if (tempnkt[k][v]>0) {

					nkt[k][v] += rhostktnormC * tempnkt[k][v];

					//reset batch counts
					tempnkt[k][v] = 0;
					//reset word counts in the last topic iteration
					if (k+1==T) {
						batch_words[v] = 0;
					}
				}

				nk[k] += nkt[k][v];

			}
		}

	}
	
	public void updateHyperParameters() {

		if(rhot_step>BURNIN_DOCUMENTS) {
			//alpha = DirichletEstimation.estimateAlpha(nmk);

			beta = DirichletEstimation.estimateAlphaLikChanging(nkt,beta,1);
			
			beta_V = beta * c.V;

		}


	}


	public double rho (int s,int tau, double kappa, int t) {
		return Double.valueOf(s)/Math.pow((tau + t),kappa);
	}

	
	
	
	
	
	
	
	

	public void save () {

		String output_base_folder = c.directory + "output_DMRTM/";
		
        File output_base_folder_file = new File(output_base_folder);
        if (!output_base_folder_file.exists()) output_base_folder_file.mkdir();
        
        String output_folder = output_base_folder + rhot_step + "/";
		
        File file = new File(output_folder);
        if (!file.exists()) file.mkdir();
		
		Save save = new Save();
		save.saveVar(nkt, output_folder+save_prefix+"nkt");
		save.close();
		save.saveVar(alpha, output_folder+save_prefix+"alpha");
		save.close();

		//We save the large document-topic file every 10 save steps, together with the perplexity
		if ((rhot_step % (SAVE_STEP *10)) == 0) {

			save.saveVar(perplexity(), output_folder+save_prefix+"perplexity");

		}
		if (rhot_step == RUNS) {

			double[][] doc_topic;
			if (store_empty) {

				//#documents including empty documents
				int Me = c.M + c.empty_documents.size();
				doc_topic = new double[Me][T];
				for (int m=0;m<Me;m++) {
					for (int k=0;k<T;k++) {
						doc_topic[m][k]  = 0;
					}
				}
				int m = 0;
				for (int me=0;me<Me;me++) {
					if (c.empty_documents.contains(me)) {
						doc_topic[me]  = new double[T];
						for (int k=0;k<T;k++) {
							doc_topic[me][k] = 1.0 / T;
						}
					}
					else {				
						doc_topic[me]  = nmk[m];
						doc_topic[me] = BasicMath.normalise(doc_topic[me]);
						m++;
					}
				}

			}
			else {
				doc_topic = new double[c.M][T];
				for (int m=0;m < c.M;m++) {
					for (int k=0;k<T;k++) {
						doc_topic[m][k]  = 0;
					}
				}
				for (int m=0;m < c.M;m++) {
					doc_topic[m]  = nmk[m];
					doc_topic[m] = BasicMath.normalise(doc_topic[m]);						
				}
			}

			save.saveVar(doc_topic, output_folder+save_prefix+"doc_topic");
			save.close();
		}

		if (topk > c.V) {
			topk = c.V;
		}


		String[][] topktopics = new String[T*2][topk];

		for (int k=0;k<T;k++) {

			List<Pair> wordprob = new ArrayList<Pair>(); 
			for (int v = 0; v < c.V; v++){
				wordprob.add(new Pair(c.dict.getWord(v), (nkt[k][v]+beta)/(nk[k]+beta_V), false));
			}
			Collections.sort(wordprob);

			for (int i=0;i<topk;i++) {
				topktopics[k*2][i] = (String) wordprob.get(i).first;
				topktopics[k*2+1][i] = String.valueOf(wordprob.get(i).second);
			}

		}
		save.saveVar(topktopics, output_folder+save_prefix+"topktopics");
		
		save.saveVar(
				"\nalpha "+ alpha+
				"\nbeta " + beta +
				"\nrhos "+rhos+
				"\nrhotau "+rhotau+
				"\nrhokappa "+rhokappa+
				"\nBATCHSIZE "+BATCHSIZE+
				"\nBURNIN "+BURNIN+
				"\nBURNIN_DOCUMENTS "+BURNIN_DOCUMENTS+
				"\nMIN_DICT_WORDS "+c.MIN_DICT_WORDS
				,output_folder+save_prefix+"others");


	}

	public double perplexity () {

		int testsize = (int) Math.floor(TRAINING_SHARE * c.M);
		if (testsize == 0) return 0;

		int totalLength = 0;
		double likelihood = 0;

		for (int m = testsize; m < c.M; m++) {
			totalLength+=c.getN(m);
		}

		int runmax = 20;



		
		for (int m = testsize; m < c.M; m++) {
			

			int[] termIDs = c.getTermIDs(m);
			short[] termFreqs = c.getTermFreqs(m);
						
			int doclength = termIDs.length;
			double[][] z = new double[doclength][T];
			
			double[] alpha_m = dmr.predict(m);

			//sample for 200 runs
			for (int RUN=0;RUN<runmax;RUN++) {
								
				//word index
				int n = 0;
				//Process words of the document
				for (int i=0;i<termIDs.length;i++) {
					
					//term index
					int t = termIDs[i];
					//How often doas t appear in the document?
					int termfreq = termFreqs[i];

					//remove old counts 
					for (int k=0;k<T;k++) {
						nmk[m][k] -= termfreq * z[n][k];
					}

					//topic probabilities - q(z)
					double[] q = new double[T];
					//sum for normalisation
					double qsum = 0.0;
					
					

					for (int k=0;k<T;k++) {

						q[k] = 	//probability of topic given feature & group
								(nmk[m][k] + alpha_m[k])
								//probability of topic given word w
								* (nkt[k][t] + beta) 
								/ (nk[k] + beta_V);


						qsum+=q[k];

					}

					//Normalise gamma (sum=1), update counts and probabilities
					for (int k=0;k<T;k++) {
						//normalise
						q[k]/=qsum;
						z[n][k]=q[k];
						nmk[m][k]+=termfreq*q[k];
					}

					n++;
				}
			}

			int n=0;
			//sampling of topic-word distribution finished - now calculate the likelihood and normalise by totalLength
			
			for (int i=0;i<termIDs.length;i++) {
				
				//term index
				int t = termIDs[i];
				//How often does t appear in the document?
				int termfreq = termFreqs[i];

				double lik = 0;
				
				for (int k=0;k<T;k++) {
					lik +=   z[n][k] * (nkt[k][t] + beta) / (nk[k] + beta_V);				
				}
				//				for (int k=0;k<T;k++) {
				//					lik +=  z[mt][n][k] * (nkt[k][t] + beta) / (nk[k] + betaV);
				//				}
				likelihood+=termfreq * Math.log(lik);



				n++;
			}

			for (int k=0;k<T;k++) nmk[m][k] = 0;

		}

		//get perplexity
		return (Math.exp(- likelihood / Double.valueOf(totalLength)));


	}


}