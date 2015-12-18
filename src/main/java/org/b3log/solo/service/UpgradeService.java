/*
 * Copyright (c) 2010-2015, b3log.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.b3log.solo.service;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import javax.inject.Inject;
import org.apache.commons.lang.StringEscapeUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.mail.MailService;
import org.b3log.latke.mail.MailServiceFactory;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.*;
import org.b3log.latke.repository.jdbc.util.Connections;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.SoloServletListener;
import org.b3log.solo.model.*;
import org.b3log.solo.repository.*;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.Thumbnails;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;

/**
 * Upgrade service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @author <a href="mailto:dongxu.wang@acm.org">Dongxu Wang</a>
 * @version 1.2.0.4, Dec 17, 2015
 * @since 1.2.0
 */
@Service
public class UpgradeService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(UpgradeService.class.getName());

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    /**
     * Step for article updating.
     */
    private static final int STEP = 50;

    /**
     * Preference Query Service.
     */
    @Inject
    private PreferenceQueryService preferenceQueryService;

    /**
     * Mail Service.
     */
    private static final MailService MAIL_SVC = MailServiceFactory.getMailService();

    /**
     * Whether the email has been sent.
     */
    private static boolean sent = false;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Old version.
     */
    private static final String FROM_VER = "1.2.0";

    /**
     * New version.
     */
    private static final String TO_VER = SoloServletListener.VERSION;

    /**
     * Upgrades if need.
     */
    public void upgrade() {
        try {
            final JSONObject preference = preferenceQueryService.getPreference();
            if (null == preference) {
                return;
            }

            final String currentVer = preference.getString(Option.ID_C_VERSION);

            if (SoloServletListener.VERSION.equals(currentVer)) {
                return;
            }

            if (FROM_VER.equals(currentVer)) {
                perform();

                return;
            }

            LOGGER.log(Level.WARN, "Attempt to skip more than one version to upgrade. Expected: {0}; Actually: {1}", FROM_VER, currentVer);

            if (!sent) {
                notifyUserByEmail();

                sent = true;
            }

        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);
            LOGGER.log(Level.ERROR,
                    "Upgrade failed [" + e.getMessage() + "], please contact the Solo developers or reports this "
                    + "issue directly (<a href='https://github.com/b3log/solo/issues/new'>"
                    + "https://github.com/b3log/solo/issues/new</a>) ");
        }
    }

    /**
     * Performs upgrade.
     *
     * @throws Exception upgrade fails
     */
    private void perform() throws Exception {
        LOGGER.log(Level.INFO, "Upgrading from version [{0}] to version [{1}]....", FROM_VER, TO_VER);

        Transaction transaction = null;

        try {
            final Connection connection = Connections.getConnection();
            final Statement statement = connection.createStatement();
            final String tablePrefix = Latkes.getLocalProperty("jdbc.tablePrefix") + "_";
            statement.execute("DROP TABLE `" + tablePrefix + "preference`");
            statement.close();
            connection.commit();
            connection.close();

            transaction = optionRepository.beginTransaction();

            final JSONObject versionOpt = optionRepository.get(Option.ID_C_VERSION);
            versionOpt.put(Option.OPTION_VALUE, TO_VER);
            optionRepository.update(Option.ID_C_VERSION, versionOpt);

            final JSONArray comments = commentRepository.get(new Query()).optJSONArray(Keys.RESULTS);
            for (int i = 0; i < comments.length(); i++) {
                final JSONObject comment = comments.getJSONObject(i);

                String name = comment.optString(Comment.COMMENT_NAME);
                name = Jsoup.clean(name, Whitelist.none());
                comment.put(Comment.COMMENT_NAME, name);

                String content = comment.optString(Comment.COMMENT_CONTENT);
                content = StringEscapeUtils.unescapeHtml(content).replaceAll("_esc_enter_88250_", "<br/>");
                content = Markdowns.toHTML(content);
                content = Jsoup.clean(content, Whitelist.relaxed());
                comment.put(Comment.COMMENT_CONTENT, content);

                commentRepository.update(comment.optString(Keys.OBJECT_ID), comment);
            }

            transaction.commit();

            LOGGER.log(Level.INFO, "Updated preference");
        } catch (final Exception e) {
            if (null != transaction && transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Upgrade failed!", e);
            throw new Exception("Upgrade failed from version [" + FROM_VER + "] to version [" + TO_VER + ']');
        }

        LOGGER.log(Level.INFO, "Upgraded from version [{0}] to version [{1}] successfully :-)", FROM_VER, TO_VER);
    }

    /**
     * Upgrades users.
     *
     * <p>
     * Password hashing.
     * </p>
     *
     * @throws Exception exception
     */
    private void upgradeUsers() throws Exception {
        final JSONArray users = userRepository.get(new Query()).getJSONArray(Keys.RESULTS);

        for (int i = 0; i < users.length(); i++) {
            final JSONObject user = users.getJSONObject(i);
            final String email = user.optString(User.USER_EMAIL);

            user.put(UserExt.USER_AVATAR, Thumbnails.getGravatarURL(email, "128"));

            userRepository.update(user.optString(Keys.OBJECT_ID), user);

            LOGGER.log(Level.INFO, "Updated user[email={0}]", email);
        }
    }

    /**
     * Upgrades articles.
     *
     * @throws Exception exception
     */
    private void upgradeArticles() throws Exception {
        LOGGER.log(Level.INFO, "Adds a property [articleEditorType] to each of articles");

        final JSONArray articles = articleRepository.get(new Query()).getJSONArray(Keys.RESULTS);

        if (articles.length() <= 0) {
            LOGGER.log(Level.TRACE, "No articles");
            return;
        }

        Transaction transaction = null;

        try {
            for (int i = 0; i < articles.length(); i++) {
                if (0 == i % STEP || !transaction.isActive()) {
                    transaction = userRepository.beginTransaction();
                }

                final JSONObject article = articles.getJSONObject(i);

                final String articleId = article.optString(Keys.OBJECT_ID);

                LOGGER.log(Level.INFO, "Found an article[id={0}]", articleId);
                article.put(Article.ARTICLE_EDITOR_TYPE, "tinyMCE");

                articleRepository.update(article.getString(Keys.OBJECT_ID), article);

                if (0 == i % STEP) {
                    transaction.commit();
                    LOGGER.log(Level.TRACE, "Updated some articles");
                }
            }

            if (transaction.isActive()) {
                transaction.commit();
            }

            LOGGER.log(Level.TRACE, "Updated all articles");
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            throw e;
        }
    }

    /**
     * Send an email to the user who upgrades Solo with a discontinuous version.
     *
     * @throws ServiceException ServiceException
     * @throws JSONException JSONException
     * @throws IOException IOException
     */
    private void notifyUserByEmail() throws ServiceException, JSONException, IOException {
        final String adminEmail = preferenceQueryService.getPreference().getString(Option.ID_C_ADMIN_EMAIL);
        final MailService.Message message = new MailService.Message();

        message.setFrom(adminEmail);
        message.addRecipient(adminEmail);
        message.setSubject(langPropsService.get("skipVersionMailSubject"));
        message.setHtmlBody(langPropsService.get("skipVersionMailBody"));

        MAIL_SVC.send(message);

        LOGGER.info("Send an email to the user who upgrades Solo with a discontinuous version.");
    }
}
