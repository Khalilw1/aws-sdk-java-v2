/*
 * Copyright 2010-2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.services.s3.internal.usearnregion;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.annotations.SdkTestInternalApi;
import software.amazon.awssdk.utils.Logger;

/**
 * {@link UseArnRegionProvider} implementation that chains together multiple useArnRegion providers.
 */
@SdkInternalApi
public final class UseArnRegionProviderChain implements UseArnRegionProvider {
    private static final Logger log = Logger.loggerFor(UseArnRegionProvider.class);
    private static final UseArnRegionProviderChain INSTANCE = new UseArnRegionProviderChain(providers(true));

    private final List<UseArnRegionProvider> providers;

    private UseArnRegionProviderChain(List<UseArnRegionProvider> providers) {
        this.providers = providers;
    }

    /**
     * Creates a default {@link UseArnRegionProviderChain}.
     *
     * <p>
     * AWS use arn region provider that looks for the useArnRegion in this order:
     *
     * <ol>
     *   <li>Check the 'aws.useArnRegion' system property for the region.</li>
     *   <li>Check the 'AWS_USE_ARN_REGION' environment variable for the region.</li>
     * </ol>
     */
    public static UseArnRegionProviderChain create() {
        return INSTANCE;
    }

    @SdkTestInternalApi
    static UseArnRegionProviderChain createWithoutCache() {
        return new UseArnRegionProviderChain(providers(false));
    }

    private static List<UseArnRegionProvider> providers(boolean cache) {
        if (cache) {
            return Arrays.asList(SystemsSettingsUseArnRegionProvider.create(), ProfileUseArnRegionProvider.createWithoutCache());
        } else {
            return Arrays.asList(SystemsSettingsUseArnRegionProvider.create(), ProfileUseArnRegionProvider.create());
        }
    }

    @Override
    public Optional<Boolean> resolveUseArnRegion() {
        for (UseArnRegionProvider provider : providers(true)) {
            try {
                Optional<Boolean> useArnRegion = provider.resolveUseArnRegion();
                if (useArnRegion.isPresent()) {
                    return useArnRegion;
                }
            } catch (Exception ex) {
                log.warn(() -> "Failed to retrieve useArnRegion from " + provider);
            }
        }
        return Optional.empty();
    }
}