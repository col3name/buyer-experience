package com.example.restservice.app.service;

import com.example.restservice.app.events.event.OnNewItemSubscriptionEvent;
import com.example.restservice.app.events.listener.OnNewSubscriptionListener;
import com.example.restservice.app.exception.SubscriptionException;
import com.example.restservice.app.model.Item;
import com.example.restservice.app.model.Subscription;
import com.example.restservice.app.model.User;
import com.example.restservice.app.repository.ItemRepository;
import com.example.restservice.app.repository.SubscriptionRepository;
import com.example.restservice.app.repository.UserRepository;
import com.example.restservice.inrostructure.config.Config;
import com.example.restservice.inrostructure.net.avitoclient.AvitoBaseException;
import com.example.restservice.inrostructure.net.avitoclient.AvitoClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Optional;

@Service
public class SubscriptionServiceImpl implements SubscriptionService {
    private final ApplicationEventPublisher eventPublisher;

    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final AvitoClient avitoClient;

    public SubscriptionServiceImpl(ApplicationEventPublisher eventPublisher, UserRepository userRepository, ItemRepository itemRepository, SubscriptionRepository subscriptionRepository, AvitoClient avitoClient) {
        this.eventPublisher = eventPublisher;
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.avitoClient = avitoClient;
    }

    @Override
    public String subscribe(final String email, final String itemUrl, final String host) {
        String itemId = parseItemId(itemUrl);
        Subscription subscription = subscriptionRepository.findByItemIdAndUserEmail(itemId, email);
        if (isDeactivatedItem(subscription)) {
            resubscribe(subscription);
            return "your resubscribe";
        } else {
            return subscribeNewItem(email, itemUrl, host, itemId);
        }
    }

    @Override
    public boolean unsubscribe(String itemId, long userId) {
        BigInteger userId1 = BigInteger.valueOf(userId);
        Subscription subscription = subscriptionRepository.findByItemIdAndUserId(itemId, userId1);

        if (subscription == null) {
            return false;
        }

        subscription.setActive(false);
        Subscription save = subscriptionRepository.save(subscription);

        eventPublisher.publishEvent(new OnNewItemSubscriptionEvent(save, "localhost"));
        return true;
    }

    @Override
    public boolean confirmSubscription(String itemId, String verificationCode, BigInteger userId) throws SubscriptionException {
        Subscription subscription = subscriptionRepository.findByItemIdAndUserId(itemId, userId);

        if (subscription == null) {
            throw new SubscriptionException("Unknown subscription");
        }
        if (!subscription.getVerificationCode().equals(verificationCode)) {
            return false;
        }
        if (subscription.isVerified()) {
            throw new SubscriptionException("Subscription already verified");
        }

        subscription.setVerified(true);
        subscriptionRepository.save(subscription);

        return true;
    }

    @Override
    public Page<Subscription> getUserSubscriptions(Long userId, Integer page, Integer limit) throws SubscriptionException {
        Optional<User> byId = this.userRepository.findById(BigInteger.valueOf(userId));
        if (byId.isEmpty()) {
            throw new SubscriptionException("Unknown user with id " + userId);
        }

        return this.subscriptionRepository.following(BigInteger.valueOf(userId), PageRequest.of(page, limit));
    }

    private boolean isDeactivatedItem(Subscription byItemIdAndUserEmail) {
        return byItemIdAndUserEmail != null && byItemIdAndUserEmail.isVerified() && !byItemIdAndUserEmail.isActive();
    }

    private void resubscribe(Subscription byItemIdAndUserEmail) {
        byItemIdAndUserEmail.setActive(true);
        subscriptionRepository.save(byItemIdAndUserEmail);
    }

    private String subscribeNewItem(String email, String itemUrl, String host, String itemId) {
        try {
            final int actualPrice = avitoClient.getActualPrice(itemId, Config.AVITO_MOBILE_API_KEY);
            Item item = initItem(itemUrl, itemId, actualPrice);
            User user = initUser(email);

            Optional<String> s = checkSubscription(host, item, user);
            if (s.isPresent()) {
                return s.get();
            }

            Subscription savedSubscription = subscriptionRepository.save(new Subscription(item, user));
            eventPublisher.publishEvent(new OnNewItemSubscriptionEvent(savedSubscription, host));

            return email + " " + itemUrl + " " + itemId;
        } catch (AvitoBaseException exception) {
            return exception.getMessage();
        }
    }

    private Item initItem(String itemUrl, String itemId, int actualPrice) {
        Item item = new Item(itemId, actualPrice, itemUrl);
        if (!itemRepository.existsById(itemId)) {
            item = itemRepository.save(item);
        } else {
            item = itemRepository.getById(itemId);
        }
        return item;
    }

    private User initUser(String email) {
        User user = new User(email);
        if (!userRepository.existsByEmail(email)) {
            user = userRepository.save(user);
        } else {
            user = userRepository.getByEmail(email);
        }
        return user;
    }

    private Optional<String> checkSubscription(String host, Item item, User user) {
        Optional<String> result = Optional.empty();
        Subscription subscription = subscriptionRepository.findByItemIdAndUserId(item.getId(), user.getId());
        if (subscription != null) {
            String message = "Subscription already exists.";
            if (!subscription.isVerified()) {
                message += " PLease confirm your subscription " + OnNewSubscriptionListener.getConfirmationUrl(subscription, host);
            }

            result = Optional.of(message);
        }

        return result;
    }

    private String parseItemId(String itemUrl) {
        return itemUrl.substring(itemUrl.lastIndexOf("_") + 1);
    }
}
