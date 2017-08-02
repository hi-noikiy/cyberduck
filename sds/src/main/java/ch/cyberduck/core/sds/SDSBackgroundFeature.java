package ch.cyberduck.core.sds;

/*
 * Copyright (c) 2002-2017 iterate GmbH. All rights reserved.
 * https://cyberduck.io/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

import ch.cyberduck.core.Host;
import ch.cyberduck.core.LocaleFactory;
import ch.cyberduck.core.LoginOptions;
import ch.cyberduck.core.PasswordCallback;
import ch.cyberduck.core.PasswordStoreFactory;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.LoginCanceledException;
import ch.cyberduck.core.features.Background;
import ch.cyberduck.core.preferences.PreferencesFactory;
import ch.cyberduck.core.sds.io.swagger.client.ApiException;
import ch.cyberduck.core.sds.io.swagger.client.api.NodesApi;
import ch.cyberduck.core.sds.io.swagger.client.api.UserApi;
import ch.cyberduck.core.sds.io.swagger.client.model.FileFileKeys;
import ch.cyberduck.core.sds.io.swagger.client.model.MissingKeysResponse;
import ch.cyberduck.core.sds.io.swagger.client.model.UserAccount;
import ch.cyberduck.core.sds.io.swagger.client.model.UserFileKeySetBatchRequest;
import ch.cyberduck.core.sds.io.swagger.client.model.UserFileKeySetRequest;
import ch.cyberduck.core.sds.io.swagger.client.model.UserIdFileIdItem;
import ch.cyberduck.core.sds.io.swagger.client.model.UserKeyPairContainer;
import ch.cyberduck.core.sds.io.swagger.client.model.UserUserPublicKey;
import ch.cyberduck.core.sds.triplecrypt.CryptoExceptionMappingService;
import ch.cyberduck.core.sds.triplecrypt.TripleCryptConverter;
import ch.cyberduck.core.threading.ScheduledThreadPool;
import ch.cyberduck.core.vault.VaultCredentials;
import ch.cyberduck.core.worker.DefaultExceptionMappingService;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import eu.ssp_europe.sds.crypto.Crypto;
import eu.ssp_europe.sds.crypto.CryptoSystemException;
import eu.ssp_europe.sds.crypto.InvalidFileKeyException;
import eu.ssp_europe.sds.crypto.InvalidKeyPairException;
import eu.ssp_europe.sds.crypto.InvalidPasswordException;
import eu.ssp_europe.sds.crypto.model.EncryptedFileKey;
import eu.ssp_europe.sds.crypto.model.PlainFileKey;
import eu.ssp_europe.sds.crypto.model.UserKeyPair;
import eu.ssp_europe.sds.crypto.model.UserPrivateKey;

public class SDSBackgroundFeature implements Background {
    private static final Logger log = Logger.getLogger(SDSBackgroundFeature.class);

    private final SDSSession session;

    private final ScheduledThreadPool userFileKeyScheduler = new ScheduledThreadPool();
    private final CountDownLatch exit = new CountDownLatch(1);

    public SDSBackgroundFeature(final SDSSession session) {
        this.session = session;
    }

    protected List<UserFileKeySetRequest> processMissingKeys(final PasswordCallback passwordCallback) {
        final List<UserFileKeySetRequest> processed = new ArrayList<>();
        try {
            // Requests a list of missing FileKeys that may be generated by the current user.
            // Clients should regularly request missing FileKeys to provide access to files for other users. The returned list is ordered by priority (Rescue Keys are returned first)
            final UserAccount account = new UserApi(session.getClient()).getUserInfo(session.getToken(), null, false);
            if(!account.getIsEncryptionEnabled()) {
                return processed;
            }
            final UserPrivateKey privateKey = new UserPrivateKey();
            final UserKeyPairContainer keyPairContainer = new UserApi(session.getClient()).getUserKeyPair(session.getToken());
            privateKey.setPrivateKey(keyPairContainer.getPrivateKeyContainer().getPrivateKey());
            privateKey.setVersion(keyPairContainer.getPrivateKeyContainer().getVersion());
            final Host bookmark = session.getHost();
            final VaultCredentials passphrase = new VaultCredentials(
                    PasswordStoreFactory.get().getPassword(bookmark.getHostname(),
                            String.format("Triple-Crypt (%s)", bookmark.getCredentials().getUsername()))) {
            };
            final UserKeyPair userKeyPair = new UserKeyPair();
            userKeyPair.setUserPrivateKey(privateKey);
            while(null == passphrase.getPassword() || !Crypto.checkUserKeyPair(userKeyPair, passphrase.getPassword())) {
                passwordCallback.prompt(passphrase, LocaleFactory.localizedString("Enter your encryption password", "Credentials"),
                        MessageFormat.format(LocaleFactory.localizedString("Enter your encryption password to decrypt {0}.", "Credentials"), StringUtils.EMPTY),
                        new LoginOptions()
                                .user(false)
                                .anonymous(false)
                                .icon(bookmark.getProtocol().disk())
                );
                if(passphrase.getPassword() == null) {
                    throw new LoginCanceledException();
                }
            }
            if(passphrase.isSaved()) {
                if(log.isInfoEnabled()) {
                    log.info("Save passphrase");
                }
                PasswordStoreFactory.get().addPassword(bookmark.getHostname(),
                        String.format("Triple-Crypt (%s)", bookmark.getCredentials().getUsername()), passphrase.getPassword());
            }
            final MissingKeysResponse missingKeys = new NodesApi(session.getClient()).missingFileKeys(session.getToken(),
                    null, null, null, null, null);
            final Map<Long, UserUserPublicKey> publicKeys =
                    missingKeys.getUsers().stream().collect(Collectors.toMap(UserUserPublicKey::getId, Function.identity()));
            final Map<Long, FileFileKeys> files =
                    missingKeys.getFiles().stream().collect(Collectors.toMap(FileFileKeys::getId, Function.identity()));
            final UserFileKeySetBatchRequest request = new UserFileKeySetBatchRequest();
            for(UserIdFileIdItem item : missingKeys.getItems()) {
                final UserUserPublicKey publicKey = publicKeys.get(item.getUserId());
                final FileFileKeys fileKeys = files.get(item.getFileId());
                final UserFileKeySetRequest keySetRequest = new UserFileKeySetRequest();
                keySetRequest.setFileId(item.getFileId());
                keySetRequest.setUserId(item.getUserId());
                processed.add(keySetRequest);
                final PlainFileKey plainFileKey = Crypto.decryptFileKey(
                        TripleCryptConverter.toCryptoEncryptedFileKey(fileKeys.getFileKeyContainer()), privateKey, passphrase.getPassword());
                final EncryptedFileKey encryptFileKey = Crypto.encryptFileKey(
                        plainFileKey, TripleCryptConverter.toCryptoUserPublicKey(publicKey.getPublicKeyContainer())
                );
                keySetRequest.setFileKey(TripleCryptConverter.toSwaggerFileKey(encryptFileKey));
                if(log.isDebugEnabled()) {
                    log.debug(String.format("Missing file key for file with id %d processed", item.getFileId()));
                }
                request.addItemsItem(keySetRequest);
            }
            if(!request.getItems().isEmpty()) {
                new NodesApi(session.getClient()).setUserFileKeys(session.getToken(), request);
            }
        }
        catch(ApiException e) {
            final BackgroundException failure = new SDSExceptionMappingService().map(e);
            log.warn(String.format("Failure refreshing missing file keys. %s", failure.getDetail()));
        }
        catch(InvalidKeyPairException | CryptoSystemException | InvalidPasswordException | InvalidFileKeyException e) {
            final BackgroundException failure = new CryptoExceptionMappingService().map(e);
            log.warn(String.format("Failure while processing missing file keys. %s", failure.getDetail()));
        }
        catch(LoginCanceledException e) {
            log.warn("Password prompt cancelled");
        }
        return processed;
    }

    @Override
    public void run(final PasswordCallback passwordCallback) throws BackgroundException {
        userFileKeyScheduler.repeat(() -> {
            this.processMissingKeys(passwordCallback);
        }, PreferencesFactory.get().getLong("sds.encryption.missingkeys.scheduler.period"), TimeUnit.MILLISECONDS);
        try {
            exit.await();
        }
        catch(InterruptedException e) {
            log.error(String.format("Error waiting for exit signal %s", e.getMessage()));
            throw new DefaultExceptionMappingService().map(e);
        }
    }

    @Override
    public void shutdown() {
        exit.countDown();
        userFileKeyScheduler.shutdown();
    }
}
