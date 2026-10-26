package io.timewheel;

class InMemoryTaskStoreTest extends TaskStoreContractTest {

    @Override
    TaskStore create() {
        return new InMemoryTaskStore();
    }
}
