const express = require('express');
const app = express();

const PORT = 7001;

app.get('/health',(req,res)=>{
    res.status(200).send('OK');
});

app.get('/api/products/:id',(req,res)=>{
    const shouldFail = Math.random() < 0.3;
    if(shouldFail){
        return res.status(500).json({
            error: "Simulated Failure",
            serveredBy: "backend-b-flaky"
        });
    }
    return res.json({
        productID : req.params.id,
        name: "Sample Product",
        serveredBy: "backend-b-flaky"
    });
}
);

app.listen(PORT, ()=>{
    console.log(`Backend-b-flaky is listening on the port ${PORT}`);
});
