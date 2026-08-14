package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComPublicKeyRegistrationEntity;
import com.crmforlogistics.messagecenter.mapper.WeComPublicKeyRegistrationMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComChatDataPublicKeyRegistrationStore {
    private final WeComPublicKeyRegistrationMapper registrationMapper;

    public WeComChatDataPublicKeyRegistrationStore(WeComPublicKeyRegistrationMapper registrationMapper) {
        this.registrationMapper = registrationMapper;
    }

    public boolean isRegistered(String authCorpId, int publicKeyVersion,
                                String publicKeySha256) {
        validateKey(authCorpId, publicKeyVersion, publicKeySha256);
        try {
            return registrationMapper.findRegistered(authCorpId, publicKeyVersion, publicKeySha256) != null;
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw stateFailed(exception);
        }
    }

    public void markRegistered(String authCorpId, int publicKeyVersion,
                               String publicKeySha256) {
        validateKey(authCorpId, publicKeyVersion, publicKeySha256);
        try {
            WeComPublicKeyRegistrationEntity entity = new WeComPublicKeyRegistrationEntity();
            entity.setAuthCorpId(authCorpId);
            entity.setPublicKeyVersion(publicKeyVersion);
            entity.setPublicKeySha256(publicKeySha256);
            registrationMapper.upsert(entity);
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw stateFailed(exception);
        }
    }

    private static void validateKey(String authCorpId, int version, String sha256) {
        if (authCorpId == null || authCorpId.isBlank() || authCorpId.length() > 128
                || version < 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw stateFailed(null);
        }
    }

    private static WeComChatDataException stateFailed(Throwable cause) {
        return new WeComChatDataException("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_STATE_FAILED", 500,
                "企业微信会话存档公钥注册状态不可用", cause);
    }
}
