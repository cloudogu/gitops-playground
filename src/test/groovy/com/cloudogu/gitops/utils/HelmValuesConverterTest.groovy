package com.cloudogu.gitops.utils

import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class HelmValuesConverterTest {
    @Test
    void 'converts deeply nested values'() {
        Map values = [
                foo: [
                        bar: [
                                baz: "hello"
                        ],
                        stuff: "fubar"
                ]
        ]


        def converter = new HelmValuesConverter()
        def result = converter.flattenValues(values)
        assertThat(result).isEqualTo([
                [name: 'foo.bar.baz', value: "hello"],
                [name: 'foo.stuff', value: "fubar"],
        ])
    }

    @Test
    void 'converts array'() {
        Map values = [
                this: [
                        is: [
                                array: [
                                        1,
                                        2,
                                        "three",
                                        [thisis: "map"],
                                        [1,2]
                                ],
                        ]
                ]
        ]


        def converter = new HelmValuesConverter()
        def result = converter.flattenValues(values)
        assertThat(result).isEqualTo([
                [name: 'this.is.array[0]', value: "1"],
                [name: 'this.is.array[1]', value: "2"],
                [name: 'this.is.array[2]', value: "three"],
                [name: 'this.is.array[3].thisis', value: "map"],
                [name: 'this.is.array[4][0]', value: "1"],
                [name: 'this.is.array[4][1]', value: "2"],
        ])
    }

    @Test
    void 'escapes dollar symbol'() {
        Map values = [
                will: [
                        "beescaped": 'aa$aa'
                ]
        ]


        def converter = new HelmValuesConverter()
        def result = converter.flattenValues(values)
        assertThat(result).isEqualTo([
                [name: 'will.beescaped', value: 'aa$$aa'],
        ])
    }
}
