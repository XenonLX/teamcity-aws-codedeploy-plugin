/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.util.amazon;

import com.amazonaws.AmazonWebServiceClient;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.codebuild.AWSCodeBuildClient;
import com.amazonaws.services.codedeploy.AmazonCodeDeployClient;
import com.amazonaws.services.codepipeline.AWSCodePipelineClient;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.Credentials;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.version.ServerVersionHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author vbedrosova
 */
public class AWSClients {

  @Nullable private final AWSCredentials myCredentials;
  @NotNull private final String myRegion;
  @NotNull private final ClientConfiguration myClientConfiguration;

  private AWSClients(@Nullable AWSCredentials credentials, @NotNull String region) {
    myCredentials = credentials;
    myRegion = region;
    myClientConfiguration = createClientConfiguration();
  }

  @NotNull
  public static AWSClients fromDefaultCredentialProviderChain(@NotNull String region) {
    return fromExistingCredentials(null, region);
  }
  @NotNull
  public static AWSClients fromBasicCredentials(@NotNull String accessKeyId, @NotNull String secretAccessKey, @NotNull String region) {
    return fromExistingCredentials(new BasicAWSCredentials(accessKeyId, secretAccessKey), region);
  }

  @NotNull
  public static AWSClients fromBasicSessionCredentials(@NotNull String accessKeyId, @NotNull String secretAccessKey, @NotNull String sessionToken, @NotNull String region) {
    return fromExistingCredentials(new BasicSessionCredentials(accessKeyId, secretAccessKey, sessionToken), region);
  }

  @NotNull
  public static AWSClients fromSessionCredentials(@NotNull final String accessKeyId, @NotNull final String secretAccessKey,
                                                  @NotNull final String iamRoleARN, @Nullable final String externalID,
                                                  @NotNull final String sessionName, final int sessionDuration,
                                                  @NotNull final String region) {
    return fromExistingCredentials(new LazyCredentials() {
      @NotNull
      @Override
      protected AWSSessionCredentials createCredentials() {
        return AWSClients.fromBasicCredentials(accessKeyId, secretAccessKey, region).createSessionCredentials(iamRoleARN, externalID, sessionName, sessionDuration);
      }
    }, region);
  }

  @NotNull
  public static AWSClients fromSessionCredentials(@NotNull final String iamRoleARN, @Nullable final String externalID,
                                                  @NotNull final String sessionName, final int sessionDuration,
                                                  @NotNull final String region) {
    return fromExistingCredentials(new LazyCredentials() {
      @NotNull
      @Override
      protected AWSSessionCredentials createCredentials() {
        return AWSClients.fromDefaultCredentialProviderChain(region).createSessionCredentials(iamRoleARN, externalID, sessionName, sessionDuration);
      }
    }, region);
  }

  @NotNull
  private static AWSClients fromExistingCredentials(@Nullable AWSCredentials credentials, @NotNull String region) {
    return new AWSClients(credentials, region);
  }

  public interface WithClient<T, C extends AmazonWebServiceClient> {
    @Nullable T run(@NotNull C client);
  }

  private interface ClientCreator<C extends AmazonWebServiceClient> {
    @NotNull C create();
  }

  @Nullable
  private <T, C extends AmazonWebServiceClient> T withClient(@NotNull WithClient<T, C> t, @NotNull ClientCreator<C> clientCreator) throws AWSException {
    final ClassLoader cl = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    try {
      try {
        return t.run(clientCreator.create());
      } catch (Throwable e) {
        throw new AWSException(e);
      }
    } finally {
      Thread.currentThread().setContextClassLoader(cl);
    }
  }

  @Nullable
  public <T> T withS3Client(@NotNull WithClient<T, AmazonS3Client> t) throws AWSException {
    return withClient(t, new ClientCreator<AmazonS3Client>() {
      @NotNull
      @Override
      public AmazonS3Client create() {
        return createS3Client();
      }
    });
  }

  @Nullable
  public <T> T withCodeDeployClient(@NotNull WithClient<T, AmazonCodeDeployClient> t) throws AWSException {
    return withClient(t, new ClientCreator<AmazonCodeDeployClient>() {
      @NotNull
      @Override
      public AmazonCodeDeployClient create() {
        return createCodeDeployClient();
      }
    });
  }

  @Nullable
  public <T> T withCodePipelineClient(@NotNull WithClient<T, AWSCodePipelineClient> t) throws AWSException {
    return withClient(t, new ClientCreator<AWSCodePipelineClient>() {
      @NotNull
      @Override
      public AWSCodePipelineClient create() {
        return createCodePipeLineClient();
      }
    });
  }

  @Nullable
  public <T> T withCodeBuildClient(@NotNull WithClient<T, AWSCodeBuildClient> t) throws AWSException {
    return withClient(t, new ClientCreator<AWSCodeBuildClient>() {
      @NotNull
      @Override
      public AWSCodeBuildClient create() {
        return createCodeBuildClient();
      }
    });
  }

  @NotNull
  private AmazonS3Client createS3Client() {
    final AmazonS3Client s3Client = withRegion(myCredentials == null ? new AmazonS3Client(myClientConfiguration) : new AmazonS3Client(myCredentials, myClientConfiguration));
    s3Client.setS3ClientOptions(S3ClientOptions.builder().setPathStyleAccess(true).build());
    return s3Client;
  }

  @NotNull
  private AmazonCodeDeployClient createCodeDeployClient() {
    return withRegion(myCredentials == null ? new AmazonCodeDeployClient(myClientConfiguration) : new AmazonCodeDeployClient(myCredentials, myClientConfiguration));
  }

  @NotNull
  private AWSCodePipelineClient createCodePipeLineClient() {
    return withRegion(myCredentials == null ? new AWSCodePipelineClient(myClientConfiguration) : new AWSCodePipelineClient(myCredentials, myClientConfiguration));
  }

  @NotNull
  private AWSCodeBuildClient createCodeBuildClient() {
    return withRegion(myCredentials == null ? new AWSCodeBuildClient(myClientConfiguration) : new AWSCodeBuildClient(myCredentials, myClientConfiguration));
  }

  @NotNull
  private AWSSecurityTokenServiceClient createSecurityTokenServiceClient() {
    return myCredentials == null ? new AWSSecurityTokenServiceClient(myClientConfiguration) : new AWSSecurityTokenServiceClient(myCredentials, myClientConfiguration);
  }

  @NotNull
  public String getRegion() {
    return myRegion;
  }

  @NotNull
  private <T extends AmazonWebServiceClient> T withRegion(@NotNull T client) {
    return client.withRegion(AWSRegions.getRegion(myRegion));
  }

  @NotNull
  private AWSSessionCredentials createSessionCredentials(@NotNull String iamRoleARN, @Nullable String externalID, @NotNull String sessionName, int sessionDuration) throws AWSException {
    final AssumeRoleRequest assumeRoleRequest = new AssumeRoleRequest().withRoleArn(iamRoleARN).withRoleSessionName(patchSessionName(sessionName)).withDurationSeconds(sessionDuration);
    if (StringUtil.isNotEmpty(externalID)) assumeRoleRequest.setExternalId(externalID);
    try {
      final Credentials credentials = createSecurityTokenServiceClient().assumeRole(assumeRoleRequest).getCredentials();
      return new BasicSessionCredentials(credentials.getAccessKeyId(), credentials.getSecretAccessKey(), credentials.getSessionToken());
    } catch (Exception e) {
      throw new AWSException(e);
    }
  }

  public static final String UNSUPPORTED_SESSION_NAME_CHARS = "[^\\w+=,.@-]";
  public static final int MAX_SESSION_NAME_LENGTH = 64;

  @NotNull
  private static String patchSessionName(@NotNull String sessionName) {
    return StringUtil.truncateStringValue(sessionName.replaceAll(UNSUPPORTED_SESSION_NAME_CHARS, "_"), MAX_SESSION_NAME_LENGTH);
  }

  @NotNull
  private static ClientConfiguration createClientConfiguration() {
    return new ClientConfiguration().withUserAgent("JetBrains TeamCity " + ServerVersionHolder.getVersion().getDisplayVersion());
  }

  // must implement AWSSessionCredentials as AWS SDK may use "instanceof"
  private static abstract class LazyCredentials implements AWSSessionCredentials {
    @Nullable
    private AWSSessionCredentials myDelegate = null;

    @Override
    public String getAWSAccessKeyId() {
      return getDelegate().getAWSAccessKeyId();
    }

    @Override
    public String getAWSSecretKey() {
      return getDelegate().getAWSSecretKey();
    }

    @Override
    public String getSessionToken() {
      return getDelegate().getSessionToken();
    }

    @NotNull
    private AWSSessionCredentials getDelegate() {
      if (myDelegate == null) myDelegate = createCredentials();
      return myDelegate;
    }

    @NotNull
    protected abstract AWSSessionCredentials createCredentials();
  }
}
