package zmq.socket.pubsub;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Test;

import zmq.Ctx;
import zmq.Msg;
import zmq.SocketBase;
import zmq.ZMQ;
import zmq.util.Utils;

public class XpubXsubTest
{
    @Test
    public void testIssue476() throws InterruptedException, IOException, ExecutionException
    {
        final int front = Utils.findOpenPort();
        final int back = Utils.findOpenPort();

        final Ctx ctx = zmq.ZMQ.createContext();
        ZMQ.setContextOption(ctx, ZMQ.ZMQ_IO_THREADS, 4);
        assertThat(ctx, notNullValue());

        boolean rc;
        final SocketBase proxyPub = zmq.ZMQ.socket(ctx, zmq.ZMQ.ZMQ_XPUB);
        rc = proxyPub.bind("tcp://127.0.0.1:" + back);
        assertThat(rc, is(true));
        final SocketBase proxySub = zmq.ZMQ.socket(ctx, zmq.ZMQ.ZMQ_XSUB);
        rc = proxySub.bind("tcp://127.0.0.1:" + front);
        assertThat(rc, is(true));

        final SocketBase ctrl = zmq.ZMQ.socket(ctx, zmq.ZMQ.ZMQ_PAIR);
        rc = ctrl.bind("inproc://ctrl-proxy");
        assertThat(rc, is(true));

        ExecutorService service = Executors.newFixedThreadPool(1);

        Future<?> proxy = service.submit(new Runnable()
        {
            @Override
            public void run()
            {
                zmq.ZMQ.proxy(proxySub, proxyPub, null, ctrl);
            }
        });
        SocketBase sub = zmq.ZMQ.socket(ctx, zmq.ZMQ.ZMQ_SUB);
        rc = zmq.ZMQ.setSocketOption(sub, zmq.ZMQ.ZMQ_SUBSCRIBE, "topic");
        assertThat(rc, is(true));

        rc = zmq.ZMQ.connect(sub, "tcp://127.0.0.1:" + back);
        assertThat(rc, is(true));

        SocketBase pub = zmq.ZMQ.socket(ctx, zmq.ZMQ.ZMQ_XPUB);

        rc = zmq.ZMQ.connect(pub, "tcp://127.0.0.1:" + front);
        assertThat(rc, is(true));

        sub.recv(ZMQ.ZMQ_DONTWAIT);

        zmq.ZMQ.msleep(1000);

        System.out.print("Send.");

        rc = pub.send(new Msg("topic".getBytes(ZMQ.CHARSET)), ZMQ.ZMQ_SNDMORE);
        assertThat(rc, is(true));

        rc = pub.send(new Msg("hop".getBytes(ZMQ.CHARSET)), 0);
        assertThat(rc, is(true));

        System.out.print("Recv.");

        Msg msg = sub.recv(0);
        assertThat(msg.size(), is(5));

        msg = sub.recv(0);
        assertThat(msg.size(), is(3));

        System.out.print("End.");

        zmq.ZMQ.close(sub);

        for (int idx = 0; idx < 2; ++idx) {
            rc = pub.send(new Msg("topic abc".getBytes(ZMQ.CHARSET)), 0);
            assertThat(rc, is(true));
            ZMQ.msleep(10);
        }
        zmq.ZMQ.close(pub);

        final SocketBase command = zmq.ZMQ.socket(ctx, zmq.ZMQ.ZMQ_PAIR);
        rc = command.connect("inproc://ctrl-proxy");
        assertThat(rc, is(true));

        command.send(new Msg("TERMINATE".getBytes(ZMQ.CHARSET)), 0);

        proxy.get();
        zmq.ZMQ.close(command);
        zmq.ZMQ.close(proxyPub);
        zmq.ZMQ.close(proxySub);
        zmq.ZMQ.close(ctrl);

        zmq.ZMQ.term(ctx);
        System.out.println("Done.");
    }
}
