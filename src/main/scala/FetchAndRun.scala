import java.security.MessageDigest

import com.amazonaws.services.batch.AWSBatchClient
import com.amazonaws.services.batch.model.{ContainerOverrides, ContainerProperties, DescribeJobDefinitionsRequest, Host, JobDefinitionType, KeyValuePair, MountPoint, RegisterJobDefinitionRequest, SubmitJobRequest, Volume}
import com.amazonaws.services.s3.AmazonS3Client

/**
 * Creates a bash script in s3 and creates a AWS Batch job based on a container that will run a script to download and
 * execute the s3 script
 */
object FetchAndRun extends App {
  println("Fetch and Run!")

  /**
   * Calculate an MD5 from a string
   * @param string the input
   * @return the MD5 digest String of the input
   */
  def calculateMD5 (string: String) = {
    val md5 = MessageDigest.getInstance("MD5")
    md5.digest(commandScript.getBytes()).foldLeft("")(_+"%02x".format(_))
  }

  /*
    The script that we want the container to run. The script will be written to S3 and then the fetch_and_run.sh
    script will pull it from s3 and run it
   */
  val commandScript = "#!/bin/bash\n\ndate\nenv\necho \"This is my simple test job!.\"\necho \"jobId: $AWS_BATCH_JOB_ID\"\nsleep 5\ndate\necho \"bye bye!!\""

  //the role for the job permissions
  val jobRoleArn = "arn:aws:iam::149209198963:role/batchJobRole"
  //The container to run in
  val imageUrl = "ubuntu"
  val jobDefinitionMemory = 500
  val jobDefinitionVCPU = 1
  //my aws queue
  val jobQueue = "default-b0db5be0-414b-11ea-8b3d-065ddb117676"
  val batchJobName = "Hello_World_using_Fetch_and_Run"
  //the s3 bucket where the script will be written and fetched from
  val bucketName = "cromwell-scripts"

  //get the md5 of the script and use it as the key
  val key = calculateMD5(commandScript )

  println(s"Script md5 = $key")

  //get the s3 client
  val s3Client = AmazonS3Client.builder.build

  //try and find the commandScript and if not then write it
  if(s3Client.doesObjectExist(bucketName, key)) {

    println(s"""Found script s3://$bucketName/$key""")

  } else {

    println(s"Script $key not found in bucket $bucketName. Creating script with content:\n$commandScript")
    s3Client.putObject(bucketName, key, commandScript)
    println(s"Created script $key")

  }

  //try and find the fetch and run job def in batch
  val batchClient = AWSBatchClient.builder.build

  //the name of the definition
  val jobDefinitionName = "00000002_fetch_and_run"

  //is there an active definition of the same name?
  val jobDefinitionRequest = new DescribeJobDefinitionsRequest()
    .withJobDefinitionName( jobDefinitionName )
    .withStatus( "ACTIVE" )

  val existingJobDefinitions = batchClient.describeJobDefinitions(jobDefinitionRequest).getJobDefinitions

  if( existingJobDefinitions.isEmpty ) {
    //can't find one of the same name so make it
    println(s"Creating $jobDefinitionName job definition")

    val registerJobDefinitionRequest = new RegisterJobDefinitionRequest()
        .withJobDefinitionName( jobDefinitionName )
        .withType( JobDefinitionType.Container )
        .withContainerProperties( new ContainerProperties()
            .withJobRoleArn( jobRoleArn )
            .withImage( imageUrl )
            //.withPrivileged(true)
            .withMemory( jobDefinitionMemory )
            .withVcpus( jobDefinitionVCPU )
            // mount the fetch and run script. The script is made available by the launch template user data
            .withVolumes( new Volume().withName("fetchAndRunScript")
                                      .withHost( new Host().withSourcePath("/usr/local/bin/fetch_and_run.sh")))
            .withMountPoints( new MountPoint().withReadOnly(true)
                                              .withSourceVolume("fetchAndRunScript")
                                              .withContainerPath("/var/scratch/fetch_and_run.sh"))
            //mount the aws cli v2 distribution so the container can access it
            .withVolumes( new Volume().withName( "awsCliHome")
                                      .withHost( new Host().withSourcePath( "/usr/local/aws-cli")))
            .withMountPoints( new MountPoint().withReadOnly(true)
                                              .withSourceVolume("awsCliHome")
                                              .withContainerPath("/usr/local/aws-cli"))
            //the command the container should run
            .withCommand("/var/scratch/fetch_and_run.sh")
        )
    val jobRegistrationResult = batchClient.registerJobDefinition(registerJobDefinitionRequest)

    println(s"Created job definition named ${jobRegistrationResult.getJobDefinitionName} " +
      s"with arn ${jobRegistrationResult.getJobDefinitionArn}")

  } else {
    println(s"Found job definition ${ existingJobDefinitions.get(0).getJobDefinitionName } revision ${ existingJobDefinitions.get(0).getRevision}")
    //todo should probably check that the definition that was found by name matches what would have been created in code??
  }

  //submit the fetch and run job to Batch
  val submitJobRequest = new SubmitJobRequest()
    .withJobName( batchJobName )
    .withJobDefinition( jobDefinitionName )
    .withJobQueue( jobQueue )
    .withContainerOverrides(
      new ContainerOverrides()
        .withEnvironment(
          //these environment variables are used by the fetch_and_run.sh script to locate the script
          new KeyValuePair().withName( "BATCH_FILE_TYPE" ).withValue( "script" ),
          new KeyValuePair().withName( "BATCH_FILE_S3_URL" ).withValue( s"""s3://$bucketName/$key""" )))

  //submit the job
  println(s"Submitting job ${submitJobRequest.getJobName}")

  val submitJobResult = batchClient.submitJob(submitJobRequest)

  println(s"Submitted job ${submitJobResult.getJobName} with job id ${submitJobResult.getJobId}")

}
