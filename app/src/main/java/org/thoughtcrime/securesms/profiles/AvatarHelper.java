package org.thoughtcrime.securesms.profiles;


import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.StreamUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.account.AccountRegistry;
import org.thoughtcrime.securesms.crypto.AttachmentSecret;
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider;
import org.thoughtcrime.securesms.crypto.ModernDecryptingPartInputStream;
import org.thoughtcrime.securesms.crypto.ModernEncryptingPartOutputStream;
import org.thoughtcrime.securesms.database.model.ProfileAvatarFileDetails;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.ByteUnit;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.whispersystems.signalservice.api.util.StreamDetails;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Iterator;

public class AvatarHelper {

  private static final String TAG = Log.tag(AvatarHelper.class);

  public static int  AVATAR_DIMENSIONS                 = 1024;
  public static long AVATAR_DOWNLOAD_FAILSAFE_MAX_SIZE = ByteUnit.MEGABYTES.toBytes(10);

  private static final String AVATAR_DIRECTORY      = "avatars";
  private static final String SELF_AVATAR_FILENAME  = "self";

  private static File   avatarDirectory;
  private static String activeAccountId;

  /**
   * Must be called during account switches so the avatar directory is re-resolved
   * for the new active account. Also clears the cached directory reference.
   */
  public static void resetForAccountSwitch(@NonNull String newAccountId) {
    activeAccountId = newAccountId;
    avatarDirectory = null;
  }

  /**
   * Copies the active account's self avatar to a fixed filename ("self") within its
   * avatar directory, so it can be loaded by the account switcher UI without knowing
   * the recipient ID or opening the inactive account's database.
   *
   * Safe to call even when no avatar exists; it is a no-op in that case.
   */
  public static void writeSelfAvatarCopy(@NonNull Context context) {
    try {
      RecipientId selfId          = Recipient.self().getId();
      File        recipientFile   = getAvatarFile(context, selfId, false);
      if (!recipientFile.exists() || recipientFile.length() == 0) return;

      File selfFile = new File(getAvatarDirectory(context), SELF_AVATAR_FILENAME);
      try (java.io.FileInputStream  fis = new java.io.FileInputStream(recipientFile);
           java.io.FileOutputStream fos = new java.io.FileOutputStream(selfFile)) {
        StreamUtil.copy(fis, fos);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to write self avatar copy", e);
    }
  }

  /**
   * Returns true if a self avatar is available for the given account.
   * Works for both active and non-active accounts.
   */
  public static boolean hasSelfAvatarForAccount(@NonNull Context context, @NonNull String accountId) {
    File selfFile = getSelfFileForAccount(context, accountId);
    return selfFile.exists() && selfFile.length() > 0;
  }

  /**
   * Returns a decrypted InputStream for the self avatar of the given account.
   * Caller must close the stream. Only call if {@link #hasSelfAvatarForAccount} is true.
   */
  public static @NonNull InputStream getSelfAvatarStreamForAccount(@NonNull Context context, @NonNull String accountId) throws IOException {
    AttachmentSecret attachmentSecret = AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret();
    return ModernDecryptingPartInputStream.createFor(attachmentSecret, getSelfFileForAccount(context, accountId), 0);
  }

  private static @NonNull File getSelfFileForAccount(@NonNull Context context, @NonNull String accountId) {
    File baseDir = context.getDir(AVATAR_DIRECTORY, Context.MODE_PRIVATE);
    return new File(new File(baseDir, accountId), SELF_AVATAR_FILENAME);
  }

  private static File getAvatarDirectory(@NonNull Context context) {
    if (avatarDirectory == null) {
      File baseDir = context.getDir(AVATAR_DIRECTORY, Context.MODE_PRIVATE);

      // Resolve the active account ID if not already set
      String accountId = activeAccountId;
      if (accountId == null) {
        try {
          AccountRegistry.AccountEntry active = AccountRegistry.getInstance(
              (Application) context.getApplicationContext()
          ).getActiveAccount();
          if (active != null) {
            accountId = active.getAccountId();
            activeAccountId = accountId;
          }
        } catch (Exception e) {
          Log.w(TAG, "Failed to resolve active account for avatar directory", e);
        }
      }

      if (accountId != null) {
        // Use a per-account subdirectory to prevent collisions between accounts
        // that share the same RecipientId values (each DB auto-increments from 1).
        File accountDir = new File(baseDir, accountId);
        if (!accountDir.exists()) {
          accountDir.mkdirs();
        }
        avatarDirectory = accountDir;
      } else {
        avatarDirectory = baseDir;
      }
    }
    return avatarDirectory;
  }

  public static long getAvatarCount(@NonNull Context context) {
    File     avatarDirectory = getAvatarDirectory(context);
    String[] results         = avatarDirectory.list();

    return results == null ? 0 : results.length;
  }

  /**
   * Retrieves an iterable set of avatars. Only intended to be used during backup.
   */
  public static Iterable<Avatar> getAvatars(@NonNull Context context) {
    File   avatarDirectory = getAvatarDirectory(context);
    File[] results         = avatarDirectory.listFiles();

    if (results == null) {
      return Collections.emptyList();
    }

    return () -> {
      return new Iterator<Avatar>() {
        int i = 0;
        @Override
        public boolean hasNext() {
          return i < results.length;
        }

        @Override
        public Avatar next() {
          File file = results[i];
          try {
            return new Avatar(getAvatar(context, RecipientId.from(file.getName())),
                              file.getName(),
                              ModernEncryptingPartOutputStream.getPlaintextLength(file.length()));
          } catch (IOException e) {
            return null;
          } finally {
            i++;
          }
        }
      };
    };
  }

  /**
   * Deletes and avatar.
   */
  public static void delete(@NonNull Context context, @NonNull RecipientId recipientId) {
    getAvatarFile(context, recipientId).delete();
    try {
      if (Recipient.self().getId().equals(recipientId)) {
        new File(getAvatarDirectory(context), SELF_AVATAR_FILENAME).delete();
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to delete self avatar copy", e);
    }
    Recipient.live(recipientId).refresh();
  }

  /**
   * Whether or not an avatar is present for the given recipient.
   */
  public static boolean hasAvatar(@NonNull Context context, @NonNull RecipientId recipientId) {
    File avatarFile = getAvatarFile(context, recipientId);
    return avatarFile.exists() && avatarFile.length() > 0;
  }

  public static @NonNull ProfileAvatarFileDetails getAvatarFileDetails(@NonNull Context context, @NonNull RecipientId recipientId) {
    File avatarFile = getAvatarFile(context, recipientId);
    if (avatarFile.exists() && avatarFile.length() > 0) {
      return new ProfileAvatarFileDetails(avatarFile.hashCode(), avatarFile.lastModified());
    }
    return ProfileAvatarFileDetails.NO_DETAILS;
  }

  /**
   * Retrieves a stream for an avatar. If there is no avatar, the stream will likely throw an
   * IOException. It is recommended to call {@link #hasAvatar(Context, RecipientId)} first.
   */
  public static @NonNull InputStream getAvatar(@NonNull Context context, @NonNull RecipientId recipientId) throws IOException {
    AttachmentSecret attachmentSecret = AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret();
    File             avatarFile       = getAvatarFile(context, recipientId);

    return ModernDecryptingPartInputStream.createFor(attachmentSecret, avatarFile, 0);
  }

  public static byte[] getAvatarBytes(@NonNull Context context, @NonNull RecipientId recipientId) throws IOException {
    return hasAvatar(context, recipientId) ? StreamUtil.readFully(getAvatar(context, recipientId))
                                           : null;
  }

  /**
   * Returns the size of the avatar on disk.
   */
  public static long getAvatarLength(@NonNull Context context, @NonNull RecipientId recipientId) {
    return ModernEncryptingPartOutputStream.getPlaintextLength(getAvatarFile(context, recipientId).length());
  }

  /**
   * Saves the contents of the input stream as the avatar for the specified recipient. If you pass
   * in null for the stream, the avatar will be deleted.
   */
  public static void setAvatar(@NonNull Context context, @NonNull RecipientId recipientId, @Nullable InputStream inputStream)
      throws IOException
  {
    if (inputStream == null) {
      delete(context, recipientId);
      return;
    }

    OutputStream outputStream = null;
    try {
      outputStream = getOutputStream(context, recipientId, false);
      StreamUtil.copy(inputStream, outputStream);
    } finally {
      StreamUtil.close(outputStream);
    }

    // Keep the fixed "self" file in sync so the account switcher can show this avatar
    // without needing to open the account's database.
    try {
      if (Recipient.self().getId().equals(recipientId)) {
        writeSelfAvatarCopy(context);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to write self avatar copy after setAvatar", e);
    }
  }

  public static void setSyncAvatar(@NonNull Context context, @NonNull RecipientId recipientId, @Nullable InputStream inputStream)
      throws IOException
  {
    if (inputStream == null) {
      delete(context, recipientId);
      return;
    }

    OutputStream outputStream = null;
    try {
      outputStream = getOutputStream(context, recipientId, true);
      StreamUtil.copy(inputStream, outputStream);
    } finally {
      StreamUtil.close(outputStream);
    }
  }

  /**
   * Retrieves an output stream you can write to that will be saved as the avatar for the specified
   * recipient. Only intended to be used for backup. Otherwise, use {@link #setAvatar(Context, RecipientId, InputStream)}.
   */
  public static @NonNull OutputStream getOutputStream(@NonNull Context context, @NonNull RecipientId recipientId, boolean isSyncAvatar) throws IOException {
    AttachmentSecret attachmentSecret = AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret();
    File             targetFile       = getAvatarFile(context, recipientId, isSyncAvatar);
    return ModernEncryptingPartOutputStream.createFor(attachmentSecret, targetFile, true).getSecond();
  }

  /**
   * Returns a {@link StreamDetails} for the local user's own avatar, or null if one does not exist.
   */
  public static @Nullable StreamDetails getSelfProfileAvatarStream(@NonNull Context context) {
    RecipientId selfId = Recipient.self().getId();

    if (!hasAvatar(context, selfId)) {
      return null;
    }

    try {
      InputStream stream = getAvatar(context, selfId);
      return new StreamDetails(stream, MediaUtil.IMAGE_JPEG, getAvatarLength(context, selfId));
    } catch (IOException e) {
      Log.w(TAG,  "Failed to read own avatar!", e);
      return null;
    }
  }

  public static @NonNull File getAvatarFile(@NonNull Context context, @NonNull RecipientId recipientId) {
    File    profileAvatar       = getAvatarFile(context, recipientId, false);
    boolean profileAvatarExists = profileAvatar.exists() && profileAvatar.length() > 0;
    File    syncAvatar          = getAvatarFile(context, recipientId, true);
    boolean syncAvatarExists    = syncAvatar.exists() && syncAvatar.length() > 0;

    if (SignalStore.settings().isPreferSystemContactPhotos() && syncAvatarExists) {
      return syncAvatar;
    } else if (profileAvatarExists) {
      return profileAvatar;
    } else if (syncAvatarExists) {
      return syncAvatar;
    }

    return profileAvatar;
  }

  private static @NonNull File getAvatarFile(@NonNull Context context, @NonNull RecipientId recipientId, boolean isSyncAvatar) {
    return new File(getAvatarDirectory(context), recipientId.serialize() + (isSyncAvatar ? "-sync" : ""));
  }

  public static class Avatar {
    private final InputStream inputStream;
    private final String      filename;
    private final long        length;

    public Avatar(@NonNull InputStream inputStream, @NonNull String filename, long length) {
      this.inputStream = inputStream;
      this.filename    = filename;
      this.length      = length;
    }

    public @NonNull InputStream getInputStream() {
      return inputStream;
    }

    public @NonNull String getFilename() {
      return filename;
    }

    public long getLength() {
      return length;
    }
  }
}
