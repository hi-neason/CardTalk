package com.cardtalk.api;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Enforces a response size limit while receiving, before allocating the complete body. */
final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
    private final int limit;
    private int received;
    private Flow.Subscription subscription;
    private boolean done;
    BoundedBody(int limit) { this.limit=limit; }
    public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; delegate.onSubscribe(subscription); }
    public void onNext(List<ByteBuffer> buffers) {
        if(done) return;
        for(var buffer:buffers) {
            if(buffer.remaining()>limit-received) {
                done=true; subscription.cancel(); delegate.onError(new IOException("Upstream response exceeded size limit")); return;
            }
            received+=buffer.remaining();
        }
        delegate.onNext(buffers);
    }
    public void onError(Throwable error) { if(!done) { done=true; delegate.onError(error); } }
    public void onComplete() { if(!done) { done=true; delegate.onComplete(); } }
}
