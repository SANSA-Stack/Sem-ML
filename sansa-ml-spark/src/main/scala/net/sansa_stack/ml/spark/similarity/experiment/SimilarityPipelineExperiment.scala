package net.sansa_stack.ml.spark.similarity.experiment

import java.util.Calendar

import net.sansa_stack.ml.spark.similarity.similarity_measures.JaccardModel
import org.apache.jena.riot.Lang
import net.sansa_stack.rdf.spark.io._
import org.apache.spark.ml.feature.{CountVectorizer, CountVectorizerModel, MinHashLSH, MinHashLSHModel, StringIndexer, Tokenizer, VectorAssembler}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import net.sansa_stack.ml.spark.utils.FeatureExtractorModel
import org.apache.spark.sql.functions.{col, lit, udf}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{DataTypes, DoubleType, IntegerType, LongType, StringType, StructField, StructType}

import scala.collection.mutable.ListBuffer

object SimilarityPipelineExperiment {
  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder
      .appName(s"MinHash  tryout") // TODO where is this displayed?
      .master("local[*]") // TODO why do we need to specify this?
      // .master("spark://172.18.160.16:3090") // to run on server
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer") // TODO what is this for?
      .getOrCreate()

    // Here we specify the hyperparameter grid
    val inputAll: Seq[String] = Seq(
      "/Users/carstendraschner/GitHub/py_rdf_sim/notebooks/sampleMovieRDF10.nt",
      "/Users/carstendraschner/GitHub/py_rdf_sim/notebooks/sampleMovieRDF10.nt",
      "/Users/carstendraschner/GitHub/py_rdf_sim/notebooks/sampleMovieRDF100.nt",
      "/Users/carstendraschner/GitHub/py_rdf_sim/notebooks/sampleMovieRDF1000.nt"
    )
    val similarityEstimationModeAll: Seq[String] = Seq("MinHash", "Jaccard")
    val parametersFeatureExtractorModeAll: Seq[String] = Seq("at", "as")
    val parameterCountVectorizerMinDfAll: Seq[Int] = Seq(1)
    val parameterCountVectorizerMaxVocabSizeAll: Seq[Int] = Seq(1000000)
    val parameterSimilarityAlphaAll: Seq[Double] = Seq(0.5)
    val parameterSimilarityBetaAll: Seq[Double] = Seq(0.5)
    val parameterNumHashTablesAll: Seq[Int] = Seq(1, 5)
    val parameterSimilarityAllPairThresholdAll: Seq[Double] = Seq(0.2, 0.8)
    val parameterSimilarityNearestNeighborsKAll: Seq[Int] = Seq(5, 20)

    // this is the path to output and we add the current datetime information
    val evaluation_datetime = Calendar.getInstance().getTime().toString
    val outputFilepath = "/Users/carstendraschner/Downloads/experimentResults" + evaluation_datetime + ".csv"

    // definition of resulting dataframe schema
    val schema = StructType(List(
      StructField("inputPath", StringType, true),
      StructField("inputFileName", StringType, true),
      StructField("inputFileSizeNumberTriples", LongType, true),
      StructField("similarityEstimationMode", StringType, true),
      StructField("parametersFeatureExtractorMode", StringType, true),
      StructField("parameterCountVectorizerMinDf", IntegerType, true),
      StructField("parameterCountVectorizerMaxVocabSize", IntegerType, true) ,
      StructField("parameterSimilarityAlpha", DoubleType, true),
      StructField("parameterSimilarityBeta", DoubleType, true),
      StructField("parameterNumHashTables", IntegerType, true),
      StructField("parameterSimilarityAllPairThreshold", DoubleType, true),
      StructField("parameterSimilarityNearestNeighborsK", IntegerType, true),
      StructField("processingTimeReadIn", DoubleType, true),
      StructField("processingTimeFeatureExtraction", DoubleType, true),
      StructField("processingTimeCountVectorizer", DoubleType, true),
      StructField("processingTimeSimilarityEstimatorSetup", DoubleType, true),
      StructField("processingTimeSimilarityEstimatorNearestNeighbors", DoubleType, true),
      StructField("processingTimeSimilarityEstimatorAllPairSimilarity", DoubleType, true),
      StructField("processingTimeTotal", DoubleType, true)
    ))

    val ex_results: scala.collection.mutable.ListBuffer[Row] = ListBuffer()
    for {
      // here we iterate over our hyperparameter room
      input <- inputAll
      similarityEstimationMode <- similarityEstimationModeAll
      parametersFeatureExtractorMode <- parametersFeatureExtractorModeAll
      parameterCountVectorizerMinDf <- parameterCountVectorizerMinDfAll
      parameterCountVectorizerMaxVocabSize <- parameterCountVectorizerMaxVocabSizeAll
      parameterSimilarityAlpha <- parameterSimilarityAlphaAll
      parameterSimilarityBeta <- parameterSimilarityBetaAll
      parameterNumHashTables <- parameterNumHashTablesAll
      parameterSimilarityAllPairThreshold <- parameterSimilarityAllPairThresholdAll
      parameterSimilarityNearestNeighborsK <- parameterSimilarityNearestNeighborsKAll
    } {
      val tmpRow: Row = run_experiment(
        spark,
        input,
        similarityEstimationMode,
        parametersFeatureExtractorMode,
        parameterCountVectorizerMinDf,
        parameterCountVectorizerMaxVocabSize,
        parameterSimilarityAlpha,
        parameterSimilarityBeta,
        parameterNumHashTables,
        parameterSimilarityAllPairThreshold,
        parameterSimilarityNearestNeighborsK
      )
      println(tmpRow)
      ex_results += tmpRow
  }

    val df: DataFrame = spark.createDataFrame(
      spark.sparkContext.parallelize(
        ex_results
      ),
      schema
    )
    // show the resulting dataframe
    df.show()
    // store the data as csv
    df.repartition(1).write.option("header", "true").format("csv").save(outputFilepath)
    // stop spark session
    spark.stop()
  }

  //noinspection ScalaStyle
  def run_experiment(
    spark: SparkSession,
    inputPath: String,
    similarityEstimationMode: String,
    parametersFeatureExtractorMode: String,
    parameterCountVectorizerMinDf: Int,
    parameterCountVectorizerMaxVocabSize: Int,
    parameterSimilarityAlpha: Double,
    parameterSimilarityBeta: Double,
    parameterNumHashTables: Int,
    parameterSimilarityAllPairThreshold: Double,
    parameterSimilarityNearestNeighborsK: Int
  ): Row = {
    // these are the parameters
    println(inputPath,
      similarityEstimationMode,
      parametersFeatureExtractorMode,
      parameterCountVectorizerMinDf,
      parameterCountVectorizerMaxVocabSize,
      parameterSimilarityAlpha,
      parameterSimilarityBeta,
      parameterNumHashTables,
      parameterSimilarityAllPairThreshold,
      parameterSimilarityNearestNeighborsK)
    // experiment Information
    val inputFileName: String = inputPath.split("/").last

    // now run experiment and keep track on processing times
    val experimentTime: Long = System.nanoTime
    var startTime: Long = System.nanoTime

    // Input Specification
    println("1: Read in data as Dataframe")
    println("\tthe used input string is: " + inputPath)
    val lang: Lang = Lang.NTRIPLES
    startTime = System.nanoTime
    val triples_df: DataFrame = spark.read.rdf(lang)(inputPath)
    val inputFileSizeNumberTriples: Long = triples_df.count()
    println("\tthe file has " + inputFileSizeNumberTriples + " triples")
    val processingTimeReadIn: Double = ((System.nanoTime - startTime) / 1e9d)
    println("\tthe read in needed " + processingTimeReadIn + "seconds")

    println("2: Dataframe based feature extractor")
    println("\tfeature extraction mode is: " + parametersFeatureExtractorMode)
    startTime = System.nanoTime
    val fe = new FeatureExtractorModel()
      .setMode(parametersFeatureExtractorMode)
      .setOutputCol("extractedFeatures")
    val fe_features = fe.transform(triples_df)
    fe_features.count()
    val processingTimeFeatureExtraction = ((System.nanoTime - startTime) / 1e9d)
    println("\tthe feature extraction needed " + processingTimeFeatureExtraction + "seconds")
    // fe_features.show()

    println("3: Count Vectorizer from MLlib")
    println("\tmax vocabsize is: " + parameterCountVectorizerMaxVocabSize)
    println("\tmin number documents it has to occur is: " + parameterCountVectorizerMinDf)
    startTime = System.nanoTime
    val cvModel: CountVectorizerModel = new CountVectorizer()
      .setInputCol("extractedFeatures")
      .setOutputCol("vectorizedFeatures")
      .setVocabSize(parameterCountVectorizerMaxVocabSize)
      .setMinDF(parameterCountVectorizerMinDf)
      .fit(fe_features)
    val cv_features: DataFrame = cvModel.transform(fe_features) // .select(col(feature_extractor_uri_column_name), col(count_vectorizer_features_column_name)) // .filter(isNoneZeroVector(col(count_vectorizer_features_column_name)))
    val isNoneZeroVector = udf({v: Vector => v.numNonzeros > 0}, DataTypes.BooleanType)
    val featuresDf: DataFrame = cv_features.filter(isNoneZeroVector(col("vectorizedFeatures"))).select("uri", "vectorizedFeatures")
    featuresDf.count()
    val processingTimeCountVectorizer: Double = ((System.nanoTime - startTime) / 1e9d)
    println("\tthe Count Vectorization needed " + processingTimeCountVectorizer + "seconds")

    var processingTimeSimilarityEstimatorSetup: Double = -1.0
    var processingTimeSimilarityEstimatorNearestNeighbors: Double = -1.0
    var processingTimeSimilarityEstimatorAllPairSimilarity: Double = -1.0

    if (similarityEstimationMode == "MinHash") {
      println("4. Similarity Estimation Process MinHash")
      println("\tthe number of hash tables is: " + parameterNumHashTables)
      startTime = System.nanoTime
      val mh: MinHashLSH = new MinHashLSH()
        .setNumHashTables(parameterNumHashTables)
        .setInputCol("vectorizedFeatures")
        .setOutputCol("hashedFeatures")
      val model: MinHashLSHModel = mh.fit(featuresDf)
      processingTimeSimilarityEstimatorSetup = ((System.nanoTime - startTime) / 1e9d)
      println("\tthe MinHash Setup needed " + processingTimeSimilarityEstimatorSetup + "seconds")


      println("4.1 Calculate nearestneigbors for one key")
      startTime = System.nanoTime
      val tmpK: Row = featuresDf.take(1)(0)
      val key: Vector = tmpK.getAs[Vector]("vectorizedFeatures")
      val keyUri: String = tmpK.getAs[String]("uri") // featuresDf.select("cv_features").collect()(0)(0).asInstanceOf[Vector]
      println(keyUri, key)
      val nnDf: DataFrame = model
        .approxNearestNeighbors(featuresDf, key, parameterSimilarityNearestNeighborsK, "distance")
        .withColumn("key_column", lit(keyUri)).select("key_column", "uri", "distance")
      nnDf.count()
      processingTimeSimilarityEstimatorNearestNeighbors = ((System.nanoTime - startTime) / 1e9d)
      println("\tNearestNeighbors needed " + processingTimeSimilarityEstimatorNearestNeighbors + "seconds")

      println("4.2 Calculate app pair similarity")
      startTime = System.nanoTime
      val simJoinDf = model.approxSimilarityJoin(featuresDf, featuresDf, parameterSimilarityAllPairThreshold, "distance") // .select("datasetA", "datasetB", "distance")
      simJoinDf.count()
      processingTimeSimilarityEstimatorAllPairSimilarity = ((System.nanoTime - startTime) / 1e9d)
      println("\tAllPairSimilarity needed " + processingTimeSimilarityEstimatorAllPairSimilarity + "seconds")
    }
    else if (similarityEstimationMode == "Jaccard") {
      println("4. Similarity Estimation Process Jaccard")
      // Similarity Estimation
      startTime = System.nanoTime
      val similarityModel = new JaccardModel()
        .set_uri_column_name_dfA("uri")
        .set_uri_column_name_dfB("uri")
        .set_features_column_name_dfA("vectorizedFeatures")
        .set_features_column_name_dfB("vectorizedFeatures")
      processingTimeSimilarityEstimatorSetup = ((System.nanoTime - startTime) / 1e9d)

      // model evaluations

      // nearest neighbor
      println("4.1 Calculate nearestneigbors for one key")
      similarityModel
        .set_uri_column_name_dfA("uri")
        .set_uri_column_name_dfB("uri")
        .set_features_column_name_dfA("vectorizedFeatures")
        .set_features_column_name_dfB("vectorizedFeatures")
      val key: Vector = cv_features.select("vectorizedFeatures").collect()(0)(0).asInstanceOf[Vector]
      startTime = System.nanoTime
      val nn_similarity_df = similarityModel.nearestNeighbors(cv_features, key, parameterSimilarityNearestNeighborsK, "theFirstUri", keep_key_uri_column = false)
      nn_similarity_df.count()
      processingTimeSimilarityEstimatorNearestNeighbors = ((System.nanoTime - startTime) / 1e9d)
      println("\tNearestNeighbors needed " + processingTimeSimilarityEstimatorNearestNeighbors + "seconds")

      // all pair
      println("4.2 Calculate app pair similarity")
      similarityModel
        .set_uri_column_name_dfA("uri")
        .set_uri_column_name_dfB("uri")
        .set_features_column_name_dfA("vectorizedFeatures")
        .set_features_column_name_dfB("vectorizedFeatures")
      startTime = System.nanoTime
      val all_pair_similarity_df: DataFrame = similarityModel.similarityJoin(featuresDf, featuresDf, parameterSimilarityAllPairThreshold)
      all_pair_similarity_df.count()
      processingTimeSimilarityEstimatorAllPairSimilarity = ((System.nanoTime - startTime) / 1e9d)
      println("\tAllPairSimilarity needed " + processingTimeSimilarityEstimatorAllPairSimilarity + "seconds")
    }
    else if (similarityEstimationMode == "Tversky") {

    }
    else {
      throw new Error("you haven't specified a working Similarity Estimation")
    }

    val processingTimeTotal: Double = ((System.nanoTime - experimentTime) / 1e9d)
    println("the complete experiment took " + processingTimeTotal + " seconds")

    // allInformation
    return Row(
      inputPath,
      inputFileName,
      inputFileSizeNumberTriples,
      similarityEstimationMode,
      parametersFeatureExtractorMode,
      parameterCountVectorizerMinDf,
      parameterCountVectorizerMaxVocabSize,
      parameterSimilarityAlpha,
      parameterSimilarityBeta,
      parameterNumHashTables,
      parameterSimilarityAllPairThreshold,
      parameterSimilarityNearestNeighborsK,
      processingTimeReadIn,
      processingTimeFeatureExtraction,
      processingTimeCountVectorizer,
      processingTimeSimilarityEstimatorSetup,
      processingTimeSimilarityEstimatorNearestNeighbors,
      processingTimeSimilarityEstimatorAllPairSimilarity,
      processingTimeTotal
    )
  }
}