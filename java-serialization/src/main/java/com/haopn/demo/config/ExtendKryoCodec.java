package com.haopn.demo.config;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import org.redisson.client.codec.Codec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ExtendKryoCodec implements Codec {

    public interface KryoPool {

        Kryo get();

        void yield(Kryo kryo);

    }

    public static class KryoPoolImpl implements ExtendKryoCodec.KryoPool {

        private final Queue<Kryo> objects = new ConcurrentLinkedQueue<Kryo>();
        private final List<Class<?>> classes;
        private final ClassLoader classLoader;

        public KryoPoolImpl(List<Class<?>> classes, ClassLoader classLoader) {
            this.classes = classes;
            this.classLoader = classLoader;
        }

        public Kryo get() {
            Kryo kryo;
            if ((kryo = objects.poll()) == null) {
                kryo = createInstance();
            }
            return kryo;
        }

        public void yield(Kryo kryo) {
            objects.offer(kryo);
        }

        /**
         * Sub classes can customize the Kryo instance by overriding this method
         *
         * @return create Kryo instance
         */
        protected Kryo createInstance() {
            Kryo kryo = new Kryo();
            if (classLoader != null) {
                kryo.setClassLoader(classLoader);
            }
            kryo.setReferences(false);
            for (Class<?> clazz : classes) {
                CompatibleFieldSerializer serializer = new CompatibleFieldSerializer(kryo, clazz);
                serializer.getCompatibleFieldSerializerConfig().setReadUnknownFieldData(true);
                kryo.register(clazz, serializer);
            }
            return kryo;
        }

    }

    public class RedissonKryoCodecException extends RuntimeException {

        private static final long serialVersionUID = 9172336149805414947L;

        public RedissonKryoCodecException(Throwable cause) {
            super(cause.getMessage(), cause);
            setStackTrace(cause.getStackTrace());
        }
    }

    private final ExtendKryoCodec.KryoPool kryoPool;

    private final Decoder<Object> decoder = new Decoder<Object>() {
        @Override
        public Object decode(ByteBuf buf, State state) throws IOException {
            Kryo kryo = null;
            try {
                kryo = kryoPool.get();
                return kryo.readClassAndObject(new Input(new ByteBufInputStream(buf)));
            } catch (Exception e) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new ExtendKryoCodec.RedissonKryoCodecException(e);
            } finally {
                if (kryo != null) {
                    kryoPool.yield(kryo);
                }
            }
        }
    };

    private final Encoder encoder = new Encoder() {

        @Override
        public byte[] encode(Object in) throws IOException {
            Kryo kryo = null;
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                Output output = new Output(baos);
                kryo = kryoPool.get();
                kryo.writeClassAndObject(output, in);
                output.close();
                return baos.toByteArray();
            } catch (Exception e) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new ExtendKryoCodec.RedissonKryoCodecException(e);
            } finally {
                if (kryo != null) {
                    kryoPool.yield(kryo);
                }
            }
        }
    };

    public ExtendKryoCodec() {
        this(Collections.<Class<?>>emptyList());
    }

    public ExtendKryoCodec(ClassLoader classLoader) {
        this(Collections.<Class<?>>emptyList(), classLoader);
    }

    public ExtendKryoCodec(List<Class<?>> classes) {
        this(classes, null);
    }

    public ExtendKryoCodec(List<Class<?>> classes, ClassLoader classLoader) {
        this(new ExtendKryoCodec.KryoPoolImpl(classes, classLoader));
    }

    public ExtendKryoCodec(ExtendKryoCodec.KryoPool kryoPool) {
        this.kryoPool = kryoPool;
    }

    @Override
    public Decoder<Object> getMapValueDecoder() {
        return getValueDecoder();
    }

    @Override
    public Encoder getMapValueEncoder() {
        return getValueEncoder();
    }

    @Override
    public Decoder<Object> getMapKeyDecoder() {
        return getValueDecoder();
    }

    @Override
    public Encoder getMapKeyEncoder() {
        return getValueEncoder();
    }

    @Override
    public Decoder<Object> getValueDecoder() {
        return decoder;
    }

    @Override
    public Encoder getValueEncoder() {
        return encoder;
    }

}



